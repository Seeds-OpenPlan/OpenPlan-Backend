package com.openplan.backend.auth.service;

import com.openplan.backend.auth.domain.AuthSessionStatus;
import com.openplan.backend.auth.domain.AuthToken;
import com.openplan.backend.auth.domain.AuthTokenType;
import com.openplan.backend.auth.repository.AuthSessionRepository;
import com.openplan.backend.auth.repository.AuthTokenRepository;
import com.openplan.backend.auth.token.AuthTokenProperties;
import com.openplan.backend.auth.token.OpaqueTokens;
import com.openplan.backend.global.config.AppProperties;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.mail.MailDispatcher;
import com.openplan.backend.global.mail.MailMessages;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.user.domain.LoginType;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.repository.UserRepository;
import com.openplan.backend.user.service.UserRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Optional;

/**
 * 비밀번호 재설정 (ST-B1-05 · AUTH-05/06) — 메일 요청과 새 비밀번호 저장.
 *
 * <p><b>요청 응답은 언제나 202다.</b> 계정이 없든, 소셜 전용이든, 쿨다운에 걸렸든 같은 응답을 준다
 * (AC2 — 계정 존재 여부 무관 동일 응답). 이메일 인증과 달리 여기서는 <b>429조차 내보내지 않는다</b>:
 * 재설정 요청은 공격자가 임의 주소로 얼마든지 던져 볼 수 있는 표면이라, 응답이 갈리는 순간
 * 가입 여부 확인 창구가 된다. openapi가 429를 열거하고 있으나 AC2가 더 구체적이라 그쪽을 따랐다.
 *
 * <p><b>성공 시 기존 세션을 전부 폐기한다</b>(AC4). 비밀번호를 바꾸는 이유가 대개 "누가 내 계정을
 * 쓰고 있다"이므로, 바꾸고도 상대의 세션이 살아 있으면 바꾼 의미가 없다.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** 프론트 라우트 — {@code ResetPasswordPage}가 {@code ?token=}을 읽는다(실측). */
    private static final String RESET_PATH = "/reset-password/confirm";

    private final UserRepository userRepository;
    private final AuthTokenRepository authTokenRepository;
    private final AuthSessionRepository authSessionRepository;
    private final MailDispatcher mailDispatcher;
    private final MailMessages mailMessages;
    private final AppProperties appProperties;
    private final AuthTokenProperties tokenProperties;
    private final PasswordEncoder passwordEncoder;
    private final UserClock clock;

    public PasswordResetService(UserRepository userRepository,
                                AuthTokenRepository authTokenRepository,
                                AuthSessionRepository authSessionRepository,
                                MailDispatcher mailDispatcher,
                                MailMessages mailMessages,
                                AppProperties appProperties,
                                AuthTokenProperties tokenProperties,
                                PasswordEncoder passwordEncoder,
                                UserClock clock) {
        this.userRepository = userRepository;
        this.authTokenRepository = authTokenRepository;
        this.authSessionRepository = authSessionRepository;
        this.mailDispatcher = mailDispatcher;
        this.mailMessages = mailMessages;
        this.appProperties = appProperties;
        this.tokenProperties = tokenProperties;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * POST /auth/password-resets — 재설정 메일 요청. 언제나 202.
     *
     * <p>소셜 전용 계정에는 보내지 않는다. 비밀번호를 심어 주면 소셜로만 만들어진 계정에
     * <b>로컬 로그인 경로가 새로 생긴다</b> — 계정 모델을 바꾸는 일이라 이 스토리 범위가 아니다.
     */
    @Transactional
    public void request(String rawEmail) {
        Instant now = clock.now();
        Optional<User> found = userRepository.findByEmail(UserRegistrationService.normalizeEmail(rawEmail));
        if (found.isEmpty()) {
            return;
        }
        User user = found.get();
        if (user.getLoginType() != LoginType.LOCAL) {
            return;
        }
        if (isThrottled(user, now)) {
            // 조용히 넘긴다 — 여기서 429를 내면 계정 존재가 드러난다.
            log.debug("재설정 메일 쿨다운으로 미발송: userId={}", user.getUserId());
            return;
        }

        String raw = OpaqueTokens.generate();
        authTokenRepository.save(AuthToken.issue(
                user.getUserId(), AuthTokenType.PASSWORD_RESET, OpaqueTokens.hash(raw),
                now.plus(tokenProperties.passwordResetTtl()), now));

        mailDispatcher.send(
                user.getEmail(),
                mailMessages.resolve("mail.password-reset.subject"),
                mailMessages.resolve("mail.password-reset.body",
                        link(raw), tokenProperties.passwordResetTtlHours()));
    }

    /**
     * PATCH /auth/password-resets/&#123;token&#125; — 새 비밀번호 저장.
     *
     * <p>모르는 토큰·만료·이미 사용됨을 모두 410 E-AUTH-006 하나로 답한다(NFR-005 1회용).
     * 구분해 알려 주면 유효한 토큰을 찾는 탐색에 단서를 준다.
     *
     * <p>여기서는 <b>멱등을 만들지 않는다.</b> 이메일 인증과 달리 두 번째 요청은 "이미 이룬 결과의 확인"이
     * 아니라 <b>다른 비밀번호로 또 바꾸려는 시도</b>이며, 링크가 한 번만 통해야 한다는 것이 규칙이다.
     */
    @Transactional
    public void complete(String rawToken, String newPassword) {
        Instant now = clock.now();
        AuthToken token = authTokenRepository.findByTokenHash(OpaqueTokens.hash(rawToken))
                .filter(t -> t.getTokenType() == AuthTokenType.PASSWORD_RESET)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_AUTH_006));

        if (!token.isUsableAt(now)) {
            token.markExpired();
            throw new OpenPlanException(ErrorCode.E_AUTH_006);
        }

        User user = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_AUTH_006));

        user.changePasswordHash(passwordEncoder.encode(newPassword));
        token.markUsed(now);

        // AC4 — 바꾸기 전에 열려 있던 세션은 전부 끊는다. 성공 경로라 같은 트랜잭션에서 해도 안전하다
        // (실패 시 함께 롤백되는 것이 옳다 — 비밀번호는 그대로인데 세션만 끊기면 안 된다).
        int revoked = authSessionRepository.revokeAllByUserIdAndStatus(user.getUserId(), AuthSessionStatus.ACTIVE);
        log.info("비밀번호 재설정 완료: userId={} 폐기된 세션={}", user.getUserId(), revoked);
    }

    private boolean isThrottled(User user, Instant now) {
        return authTokenRepository
                .findTopByUserIdAndTokenTypeOrderByCreatedAtDesc(user.getUserId(), AuthTokenType.PASSWORD_RESET)
                .map(latest -> now.isBefore(latest.getCreatedAt().plus(tokenProperties.resendCooldown())))
                .orElse(false);
    }

    private String link(String rawToken) {
        return UriComponentsBuilder.fromUriString(appProperties.frontendUrl(RESET_PATH))
                .queryParam("token", rawToken)
                .build().encode().toUriString();
    }
}
