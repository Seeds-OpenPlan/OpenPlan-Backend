package com.openplan.backend.auth.service;

import com.openplan.backend.auth.domain.AuthToken;
import com.openplan.backend.auth.domain.AuthTokenType;
import com.openplan.backend.auth.repository.AuthTokenRepository;
import com.openplan.backend.auth.token.AuthTokenProperties;
import com.openplan.backend.auth.token.OpaqueTokens;
import com.openplan.backend.global.config.AppProperties;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.mail.MailDispatcher;
import com.openplan.backend.global.mail.MailMessages;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.repository.UserRepository;
import com.openplan.backend.user.service.UserRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 이메일 인증 (ST-B1-04 · AUTH-04) — 인증 메일 발송(재발송 겸용)과 확정.
 *
 * <p><b>가입 직후의 문턱을 여는 것이 이 서비스의 역할이다.</b> 로그인은 {@code is_email_verified}가
 * true여야 통과하므로(403 E-AUTH-005), 여기가 동작하지 않으면 새 계정은 아무도 쓸 수 없다.
 *
 * <p><b>존재하지 않는 계정에도 202로 답한다.</b> "그 이메일은 가입돼 있지 않다"를 알려 주면
 * 가입 여부 확인 창구가 된다. 다만 재발송 쿨다운(429)은 계정이 있을 때만 나므로,
 * <b>직전 60초 안에 발송이 있었던 계정</b>에 한해 존재가 드러난다 — 명세가 카운트다운 UX를 위해
 * 429를 요구해(ST-B1-04 · ui-spec §AUTH) 감수한 좁은 노출이다.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** 프론트 라우트 — {@code VerifyEmailPage}가 {@code ?token=}을 읽는다(실측). */
    private static final String VERIFY_PATH = "/verify-email";

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final MailDispatcher mailDispatcher;
    private final MailMessages mailMessages;
    private final AppProperties appProperties;
    private final AuthTokenProperties tokenProperties;
    private final UserClock clock;

    public EmailVerificationService(UserRepository userRepository,
                                    AuthTokenRepository authTokenRepository,
                                    MailDispatcher mailDispatcher,
                                    MailMessages mailMessages,
                                    AppProperties appProperties,
                                    AuthTokenProperties tokenProperties,
                                    UserClock clock) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.mailDispatcher = mailDispatcher;
        this.mailMessages = mailMessages;
        this.appProperties = appProperties;
        this.tokenProperties = tokenProperties;
        this.clock = clock;
    }

    /**
     * POST /auth/email-verifications — 발송·재발송 겸용. 응답은 202(접수)다.
     *
     * <p>보내지 <b>않는</b> 경우가 둘 있고 둘 다 202로 답한다: 계정이 없을 때(열거 방지),
     * 이미 인증을 마쳤을 때(보낼 이유가 없다). 쿨다운에 걸린 경우만 429다.
     */
    @Transactional
    public void send(String rawEmail) {
        Instant now = clock.now();
        Optional<User> found = userRepository.findByEmail(UserRegistrationService.normalizeEmail(rawEmail));
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        if (user.isEmailVerified()) {
            return;
        }

        assertNotThrottled(user, now);

        String raw = OpaqueTokens.generate();
        authTokenRepository.save(AuthToken.issue(
                user.getUserId(), AuthTokenType.EMAIL_VERIFICATION, OpaqueTokens.hash(raw),
                now.plus(tokenProperties.emailVerificationTtl()), now));

        mailDispatcher.send(
                user.getEmail(),
                mailMessages.resolve("mail.email-verification.subject"),
                mailMessages.resolve("mail.email-verification.body",
                        link(raw), tokenProperties.emailVerificationTtlHours()));
    }

    /**
     * POST /auth/email-verifications/confirmation — 링크 확정.
     *
     * <p><b>멱등이다</b>(openapi: "인증 완료(멱등 — 기인증도 200)"). 사용자가 링크를 두 번 누르는 것은
     * 흔한 일이고, 두 번째에 오류를 주면 <b>성공한 동작을 실패로 보이게</b> 만든다.
     * 그래서 이미 사용된 토큰이라도 그 계정이 인증을 마쳤다면 200으로 답한다.
     *
     * <p>모르는 토큰과 만료된 토큰은 410 E-AUTH-004 — 프론트가 재발송을 유도한다.
     */
    @Transactional
    public void confirm(String rawToken) {
        Instant now = clock.now();
        AuthToken token = authTokenRepository.findByTokenHash(OpaqueTokens.hash(rawToken))
                .filter(t -> t.getTokenType() == AuthTokenType.EMAIL_VERIFICATION)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_AUTH_004));

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_AUTH_004));

        if (!token.isUsableAt(now)) {
            // 이미 쓴 링크를 다시 눌렀는데 계정이 인증돼 있다면 사용자 입장에서는 성공이다.
            if (user.isEmailVerified()) {
                return;
            }
            token.markExpired();
            throw new OpenPlanException(ErrorCode.E_AUTH_004);
        }

        user.verifyEmail();
        token.markUsed(now);
    }

    /**
     * 재발송 쿨다운 — 60초(ui-spec §AUTH 정본). 남은 초를 {@code details.retryAfterSeconds}로 실어
     * 프론트가 카운트다운을 그릴 수 있게 한다.
     */
    private void assertNotThrottled(User user, Instant now) {
        Duration cooldown = tokenProperties.resendCooldown();
        authTokenRepository
                .findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(user.getUserId(), AuthTokenType.EMAIL_VERIFICATION)
                .ifPresent(latest -> {
                    Instant nextAllowed = latest.getCreatedAt().plus(cooldown);
                    if (now.isBefore(nextAllowed)) {
                        long remain = Math.max(1, Duration.between(now, nextAllowed).toSeconds());
                        log.debug("인증 메일 재발송 쿨다운: userId={} remain={}s", user.getUserId(), remain);
                        throw new OpenPlanException(ErrorCode.E_COM_007, Map.of("retryAfterSeconds", remain));
                    }
                });
    }

    private String link(String rawToken) {
        return UriComponentsBuilder.fromUriString(appProperties.frontendUrl(VERIFY_PATH))
                .queryParam("token", rawToken)
                .build().encode().toUriString();
    }
}
