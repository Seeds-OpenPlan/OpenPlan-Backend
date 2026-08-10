package com.openplan.backend.auth.service;

import com.openplan.backend.auth.domain.AuthSession;
import com.openplan.backend.auth.domain.AuthSessionStatus;
import com.openplan.backend.auth.dto.LoginRequest;
import com.openplan.backend.auth.dto.SessionInfo;
import com.openplan.backend.auth.dto.TokenRefreshResponse;
import com.openplan.backend.auth.repository.AuthSessionRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.security.AuthCookies;
import com.openplan.backend.global.security.JwtService;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.onboarding.domain.OnboardingProgress;
import com.openplan.backend.onboarding.repository.OnboardingProgressRepository;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserProfile;
import com.openplan.backend.user.domain.UserStatus;
import com.openplan.backend.user.repository.UserProfileRepository;
import com.openplan.backend.user.repository.UserRepository;
import com.openplan.backend.user.service.UserRegistrationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 인증 서비스 (ST-B1-02 · AUTH-01/07/08 · ACCT-03) — 로그인·세션 확인·로그아웃·토큰 회전.
 *
 * <p><b>판정 순서가 보안 요건이다.</b> 자격(비밀번호)을 먼저 확인하고 <b>그 뒤에</b> 계정 상태를
 * 드러낸다. 순서를 뒤집으면 비밀번호를 모르는 사람이 "이 이메일은 잠긴 계정"·"비활성 계정"을 알아낼 수 있어,
 * AC2가 요구하는 계정 열거 방지(E-AUTH-001 동일 문구)가 무의미해진다.
 *
 * <p><b>dev 스텁 환경에서는 토큰을 발급하지 않는다.</b> {@link JwtService}·{@link AuthCookies}는
 * {@code op.auth.dev-stub=false}일 때만 등록되므로(D-32 — 팀원 로컬이 시크릿 없이 뜨도록),
 * 스텁으로 도는 로컬에서 로그인·갱신을 부르면 스텁 시절과 같은 501 E-AUTH-011로 응답한다.
 * 세션 확인·로그아웃은 {@code DevUserAuthFilter}가 주체를 주므로 그대로 동작한다.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final OnboardingProgressRepository onboardingProgressRepository;
    private final AuthSessionRepository authSessionRepository;
    private final AuthSessionTerminator terminator;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    private final ObjectProvider<JwtService> jwtServiceProvider;
    private final ObjectProvider<AuthCookies> authCookiesProvider;
    private final UserClock clock;

    public AuthService(UserRepository userRepository,
                       UserProfileRepository userProfileRepository,
                       OnboardingProgressRepository onboardingProgressRepository,
                       AuthSessionRepository authSessionRepository,
                       AuthSessionTerminator terminator,
                       org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                       ObjectProvider<JwtService> jwtServiceProvider,
                       ObjectProvider<AuthCookies> authCookiesProvider,
                       UserClock clock) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.onboardingProgressRepository = onboardingProgressRepository;
        this.authSessionRepository = authSessionRepository;
        this.terminator = terminator;
        this.passwordEncoder = passwordEncoder;
        this.jwtServiceProvider = jwtServiceProvider;
        this.authCookiesProvider = authCookiesProvider;
        this.clock = clock;
    }

    /** 로그인 결과 — 본문과 함께 나갈 쿠키 2장. 쿠키 기록은 컨트롤러가 한다. */
    public record LoginResult(SessionInfo session, ResponseCookie accessCookie, ResponseCookie refreshCookie) {
    }

    /** 재발급 결과 — 회전된 쿠키 2장 + 복귀 경로. */
    public record RefreshResult(TokenRefreshResponse body, ResponseCookie accessCookie, ResponseCookie refreshCookie) {
    }

    /** 로그아웃 결과 — 즉시 만료시킬 쿠키 2장(Max-Age=0). */
    public record LogoutResult(ResponseCookie accessCookie, ResponseCookie refreshCookie) {
    }

    // ------------------------------------------------------------------ 로그인

    /**
     * POST /auth/sessions (AUTH-01).
     *
     * <p>실패 분기(AC2): 자격 불일치 401 E-AUTH-001 · 잠금 401 E-AUTH-002 ·
     * 비활성(복구창 내) 409 E-AUTH-008 · 삭제됨 410 E-AUTH-009 · 이메일 미인증 403 E-AUTH-005.
     */
    @Transactional
    public LoginResult login(LoginRequest request) {
        JwtService jwt = requireJwt();
        AuthCookies cookies = requireCookies();
        Instant now = clock.now();

        User user = authenticate(request);
        assertLoginAllowed(user, now);

        OpenedSession opened = openSession(jwt, user.getUserId(), null, now);

        return new LoginResult(
                assembleSession(user),
                cookies.access(jwt.issueAccessToken(user.getUserId())),
                cookies.refresh(opened.rawRefreshToken()));
    }

    /**
     * 자격 확인 — 계정 부재·소셜 전용 계정·비밀번호 불일치를 <b>모두 같은 401 E-AUTH-001</b>로 합친다.
     *
     * <p>소셜 전용 계정({@code password_hash IS NULL})도 여기서 갈라지지 않는다. "이 이메일은 구글로
     * 가입돼 있다"는 응답은 친절해 보이지만 계정 존재를 확인해 주는 것과 같다.
     */
    private User authenticate(LoginRequest request) {
        String email = UserRegistrationService.normalizeEmail(request.email());
        Optional<User> found = userRepository.findByEmail(email);
        if (found.isEmpty()) {
            throw new OpenPlanException(ErrorCode.E_AUTH_001);
        }
        User user = found.get();
        String hash = user.getPasswordHash();
        if (hash == null || !passwordEncoder.matches(request.password(), hash)) {
            throw new OpenPlanException(ErrorCode.E_AUTH_001);
        }
        return user;
    }

    /**
     * 자격이 맞은 뒤에만 부르는 상태 판정. 여기서부터는 계정 상태를 드러내도 열거 위험이 없다 —
     * 이미 비밀번호를 아는 사람이다.
     */
    private void assertLoginAllowed(User user, Instant now) {
        UserStatus status = user.getStatus();

        if (status == UserStatus.LOCKED) {
            throw new OpenPlanException(ErrorCode.E_AUTH_002);
        }

        if (status == UserStatus.DEACTIVATED) {
            Instant deletionAt = user.getScheduledDeletionAt();
            // 복구창이 지났으면 삭제 배치를 아직 못 돌았더라도 사용자에겐 삭제된 계정이다(ACCT-06).
            if (deletionAt != null && !now.isBefore(deletionAt)) {
                throw new OpenPlanException(ErrorCode.E_AUTH_009);
            }
            Map<String, Object> details = new HashMap<>();
            details.put("recoverableUntil", deletionAt);
            // TODO(ST-B1-06): details.reactivationTicket — 소셜 계정 재활성화용 서명 티켓.
            //   발급 규칙이 계약에 없어 여기서 만들지 않는다. 로컬 계정은 자격으로 재활성화하므로
            //   (openapi /auth/reactivations: "로컬 = credentials / 소셜 = reactivationTicket") 이 경로는 막히지 않는다.
            throw new OpenPlanException(ErrorCode.E_AUTH_008, details);
        }

        if (!user.isEmailVerified()) {
            // 계정 상태(ACTIVE)와 인증 완료 여부는 별개 축이다 — User javadoc 참조.
            throw new OpenPlanException(ErrorCode.E_AUTH_005, Map.of("resendAvailable", true));
        }
    }

    // ------------------------------------------------------------- 세션 확인

    /** GET /auth/session (AUTH-07) — 주체는 필터가 이미 확인했다. 여기서는 표시용 정보만 조립한다. */
    @Transactional(readOnly = true)
    public SessionInfo currentSession(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        return assembleSession(user);
    }

    // --------------------------------------------------------------- 로그아웃

    /**
     * DELETE /auth/session (ACCT-03) — 세션 폐기 + 쿠키 소거.
     *
     * <p><b>쿠키가 없거나 모르는 세션이어도 성공(204)이다.</b> 로그아웃은 멱등해야 하고,
     * 여기서 404를 주면 "그 토큰은 존재하지 않는다"를 알려 주는 셈이 된다.
     */
    @Transactional
    public LogoutResult logout(String rawRefreshToken) {
        AuthCookies cookies = requireCookies();
        JwtService jwt = jwtServiceProvider.getIfAvailable();

        if (jwt != null && rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            authSessionRepository.findByRefreshTokenHash(jwt.hashRefreshToken(rawRefreshToken))
                    .ifPresent(AuthSession::revoke);
        }
        return new LogoutResult(cookies.clearAccess(), cookies.clearRefresh());
    }

    // ------------------------------------------------------------- 토큰 회전

    /**
     * POST /auth/token-refresh (AUTH-07/08) — refresh 회전.
     *
     * <p><b>재사용 탐지</b>가 이 메서드의 핵심이다. 해시로 세션을 찾았는데 상태가 ACTIVE가 아니면
     * 이미 소진·폐기된 토큰이 다시 온 것이고, 정상 클라이언트는 그런 요청을 보내지 않는다 —
     * 탈취로 보고 <b>그 사용자의 살아 있는 세션을 전부 폐기</b>한다(ADR-0001 · AC3).
     * 공격자가 훔친 토큰을 쓰면 진짜 사용자도 함께 튕겨 나오지만, 그것이 의도다: 조용히 공유되는 것보다 낫다.
     *
     * <p>실패는 원인과 무관하게 401 E-AUTH-007 하나다 — 무효·만료·재사용을 구분해 알려 줄 이유가 없다.
     */
    @Transactional
    public RefreshResult refresh(String rawRefreshToken) {
        JwtService jwt = requireJwt();
        AuthCookies cookies = requireCookies();
        Instant now = clock.now();

        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new OpenPlanException(ErrorCode.E_AUTH_007);
        }

        AuthSession session = authSessionRepository
                .findByRefreshTokenHash(jwt.hashRefreshToken(rawRefreshToken))
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_AUTH_007));

        if (session.getStatus() != AuthSessionStatus.ACTIVE) {
            // 재사용 — 이 사용자의 모든 활성 세션을 끊는다.
            // 🔴 별도 트랜잭션이어야 한다: 아래 예외가 이 트랜잭션을 롤백시키므로 같은 tx에서 폐기하면
            //    폐기까지 되돌아간다({@link AuthSessionTerminator} javadoc — 테스트가 잡은 결함).
            terminator.revokeAllActive(session.getUserId());
            throw new OpenPlanException(ErrorCode.E_AUTH_007);
        }

        if (!session.isUsableAt(now)) {
            // 정상 만료 — 재사용이 아니므로 다른 세션은 건드리지 않는다. 같은 이유로 별도 트랜잭션.
            terminator.markExpired(session.getSessionId());
            throw new OpenPlanException(ErrorCode.E_AUTH_007);
        }

        String previousPath = session.getPreviousPath();
        session.expire();   // 회전: 소진 표시를 남겨 다음 재사용을 잡을 수 있게 한다
        OpenedSession rotated = openSession(jwt, session.getUserId(), previousPath, now);

        return new RefreshResult(
                new TokenRefreshResponse(previousPath),
                cookies.access(jwt.issueAccessToken(session.getUserId())),
                cookies.refresh(rotated.rawRefreshToken()));
    }

    // ------------------------------------------------------------------ 내부

    /**
     * 개시된 세션과 그 <b>원문</b> refresh. 원문은 영속되지 않으므로(해시만 저장) 쿠키를 만들 때까지
     * 호출 사슬을 타고 전달돼야 한다 — 저장소에서 되찾을 수 없기 때문이다.
     */
    private record OpenedSession(AuthSession session, String rawRefreshToken) {
    }

    /** 새 refresh 세션 개시 — 로그인과 회전이 공유한다(만료 기준을 한 곳에서 계산하도록). */
    private OpenedSession openSession(JwtService jwt, UUID userId, String previousPath, Instant now) {
        String raw = jwt.issueRefreshToken();
        AuthSession session = AuthSession.start(
                userId, jwt.hashRefreshToken(raw), jwt.refreshExpiresAt(), previousPath, now);
        authSessionRepository.save(session);
        return new OpenedSession(session, raw);
    }

    private SessionInfo assembleSession(User user) {
        UserProfile profile = userProfileRepository.findByUserId(user.getUserId())
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        OnboardingProgress progress = onboardingProgressRepository.findById(user.getUserId())
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        return SessionInfo.of(user, profile, progress);
    }

    private JwtService requireJwt() {
        JwtService jwt = jwtServiceProvider.getIfAvailable();
        if (jwt == null) {
            throw new OpenPlanException(ErrorCode.E_AUTH_011);
        }
        return jwt;
    }

    private AuthCookies requireCookies() {
        AuthCookies cookies = authCookiesProvider.getIfAvailable();
        if (cookies == null) {
            throw new OpenPlanException(ErrorCode.E_AUTH_011);
        }
        return cookies;
    }
}
