package com.openplan.backend.auth.service;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthException;
import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.auth.oauth.OAuthProviderType;
import com.openplan.backend.auth.oauth.OAuthUserInfo;
import com.openplan.backend.global.config.AppProperties;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.security.JwtService;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.user.domain.User;
import com.openplan.backend.user.domain.UserStatus;
import com.openplan.backend.user.repository.UserRepository;
import com.openplan.backend.user.service.UserRegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Optional;

/**
 * 소셜 로그인 서비스 (ST-B1-03 · AUTH-02) — 인가 시작 URL 조립과 콜백 처리.
 *
 * <p><b>신원의 기준은 제공자 측 ID</b>({@code social_provider_user_id})다. 이메일로 찾으면 사용자가
 * 제공자에서 이메일을 바꾸는 순간 다른 사람이 된다. 이메일은 <b>계정을 처음 만들 때만</b> 쓰인다.
 *
 * <p><b>계정 연동은 하지 않는다</b>(AC2). 같은 이메일의 기존 계정이 있으면 소셜 계정을 따로 만들지 않고
 * 안내로 끝낸다 — US에 계정 연동 스토리가 없고, 임의로 이어 붙이면 "구글로 로그인했더니 남의 계획이 보인다"가
 * 될 수 있다. 되돌릴 수 없는 쪽으로 기울지 않는다.
 */
@Service
public class OAuthLoginService {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginService.class);

    /** 콜백 경로 — 제공자 콘솔에 등록된 값과 동일해야 한다(be1-notes 공통 규칙). */
    private static final String CALLBACK_PATH_TEMPLATE = "/api/v1/auth/oauth/%s/callback";

    private final OAuthProperties oauthProperties;
    private final OAuthClient oauthClient;
    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final UserRegistrationService userRegistrationService;
    private final AuthService authService;
    private final ObjectProvider<JwtService> jwtServiceProvider;
    private final UserClock clock;

    public OAuthLoginService(OAuthProperties oauthProperties,
                             OAuthClient oauthClient,
                             AppProperties appProperties,
                             UserRepository userRepository,
                             UserRegistrationService userRegistrationService,
                             AuthService authService,
                             ObjectProvider<JwtService> jwtServiceProvider,
                             UserClock clock) {
        this.oauthProperties = oauthProperties;
        this.oauthClient = oauthClient;
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.userRegistrationService = userRegistrationService;
        this.authService = authService;
        this.jwtServiceProvider = jwtServiceProvider;
        this.clock = clock;
    }

    /** 콜백 결과 — 브라우저를 보낼 곳과 함께 실을 쿠키. */
    public record CallbackResult(String redirectUrl, AuthService.LoginResult session) {
    }

    // ------------------------------------------------------------- 인가 시작

    /**
     * 제공자 인가 페이지 URL 조립 (302 대상).
     *
     * <p>{@code state}는 서명된 자기완결 토큰이라 서버에 저장하지 않는다({@link JwtService#issueOAuthState}).
     */
    public String authorizationUrl(OAuthProviderType provider) {
        OAuthProperties.Client client = oauthProperties.client(provider)
                .orElseThrow(() -> new OAuthException(ErrorCode.E_AUTH_010.code(),
                        "소셜 제공자 자격증명이 설정되지 않았습니다: " + provider));

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(provider.authorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", client.clientId())
                .queryParam("redirect_uri", redirectUri(provider))
                .queryParam("state", requireJwt().issueOAuthState(provider.pathValue()));

        if (!provider.scope().isBlank()) {
            builder.queryParam("scope", provider.scope());
        }
        // build().encode() 여야 한다. build(true)는 "값이 이미 인코딩됨"을 뜻해,
        // 공백이 든 scope("openid email profile")에서 예외가 난다 — 실제로 500으로 터졌던 자리다.
        return builder.build().encode().toUriString();
    }

    // ----------------------------------------------------------------- 콜백

    /**
     * 콜백 처리 — 코드 교환 → 사용자 정보 → 계정 확정 → 세션 발급.
     *
     * <p>실패는 전부 {@link OAuthException}이며 컨트롤러가 302로 바꾼다.
     */
    @Transactional
    public CallbackResult handleCallback(OAuthProviderType provider, String code, String state) {
        assertStateMatches(provider, state);

        OAuthProperties.Client client = oauthProperties.client(provider)
                .orElseThrow(() -> new OAuthException(ErrorCode.E_AUTH_010.code(),
                        "소셜 제공자 자격증명이 설정되지 않았습니다: " + provider));

        String accessToken = oauthClient.exchangeCodeForAccessToken(provider, client, code, redirectUri(provider));
        OAuthUserInfo info = oauthClient.fetchUserInfo(provider, accessToken);

        User user = resolveAccount(provider, info);
        assertLoginAllowed(user);

        AuthService.LoginResult session = authService.establishSession(user);
        // 🔴 프론트 화면 주소다 — API 경로가 아니다. 대시보드 화면은 라우터의 인덱스 라우트 "/"(HomePage)이고
        //    "/dashboard" 는 조립 API(GET /api/v1/dashboard)의 주소일 뿐 프론트에는 그런 라우트가 없다.
        //    "/dashboard" 로 보내면 SPA 폴백이 index.html 을 주고 라우터가 `path: '*'` → NotFoundPage 로
        //    떨어진다 — 헤더는 그대로라 "로그인은 됐는데 404" 로 보인다(2026-08-28 실사용 신고).
        String target = session.session().onboardingCompleted() ? "/" : "/onboarding";
        return new CallbackResult(appProperties.frontendUrl(target), session);
    }

    /**
     * state 검증 — 서명이 유효하고 <b>발급 때의 제공자와 콜백 경로의 제공자가 같아야</b> 한다.
     * 같은 사용자의 유효한 구글 state를 카카오 콜백에 붙여 넣는 식의 교차 사용을 막는다.
     */
    private void assertStateMatches(OAuthProviderType provider, String state) {
        String signedFor = requireJwt().parseOAuthState(state)
                .orElseThrow(() -> new OAuthException(ErrorCode.E_AUTH_010.code(), "state 검증 실패"));
        if (!provider.pathValue().equals(signedFor)) {
            throw new OAuthException(ErrorCode.E_AUTH_010.code(),
                    "state 제공자 불일치: signed=" + signedFor + " path=" + provider.pathValue());
        }
    }

    /**
     * 계정 확정 — 재로그인이면 기존 계정, 처음이면 생성.
     *
     * <p>이메일이 없으면 여기서 끝난다. {@code users.email}이 NOT NULL UNIQUE라 계정을 만들 수 없고,
     * 임의값을 넣으면 나중에 같은 사람이 다른 경로로 가입할 때 충돌한다. 카카오는 이메일이 선택 동의라
     * 실제로 일어나는 경우다 — 필수화하려면 비즈앱 전환+검수가 필요하다(be1-notes, 미결).
     */
    private User resolveAccount(OAuthProviderType provider, OAuthUserInfo info) {
        Optional<User> existing = userRepository
                .findBySocialProviderAndSocialProviderUserId(provider.socialProvider(), info.providerUserId());
        if (existing.isPresent()) {
            return existing.get();
        }

        if (!info.hasEmail()) {
            log.warn("소셜 로그인에 이메일이 없어 계정을 만들 수 없습니다: provider={}", provider);
            throw new OAuthException(ErrorCode.E_AUTH_010.code(), "제공자가 이메일을 제공하지 않았습니다: " + provider);
        }

        String email = UserRegistrationService.normalizeEmail(info.email());
        Optional<User> sameEmail = userRepository.findByEmail(email);
        if (sameEmail.isPresent()) {
            // AC2 — 별개 계정을 만들지 않고, 무엇을 해야 하는지 알 수 있는 코드로 돌려보낸다.
            throw new OAuthException(ErrorCode.E_AUTH_003.code(),
                    "같은 이메일의 기존 계정이 있습니다: provider=" + provider);
        }

        return userRegistrationService.registerSocial(email, provider.socialProvider(), info.providerUserId());
    }

    /**
     * 상태 판정. 로컬 로그인과 결론은 같지만 <b>전달 방식이 다르다</b> — 브라우저 리다이렉트라
     * 오류 봉투를 볼 수 없어 코드만 쿼리로 싣는다.
     *
     * <p>🔴 비활성 계정의 {@code /auth/reactivate?ticket=…}(openapi 302 설명)은 <b>구현하지 않았다.</b>
     * 1회용 티켓의 발급·소각 규칙이 계약에 없고, 보관할 자리({@code auth_tokens}의 토큰 값 컬럼)도 아직 없다.
     * 재활성화 자체가 ST-B1-06 소관이므로 그때 함께 정한다. 지금은 코드만 실어 보낸다.
     */
    private void assertLoginAllowed(User user) {
        UserStatus status = user.getStatus();
        if (status == UserStatus.LOCKED) {
            throw new OAuthException(ErrorCode.E_AUTH_002.code(), "잠금 계정");
        }
        if (status == UserStatus.DEACTIVATED) {
            Instant deletionAt = user.getScheduledDeletionAt();
            boolean recoverable = deletionAt == null || clock.now().isBefore(deletionAt);
            throw new OAuthException(
                    recoverable ? ErrorCode.E_AUTH_008.code() : ErrorCode.E_AUTH_009.code(),
                    "비활성/삭제 계정");
        }
    }

    /** 인가와 토큰 교환이 <b>같은 문자열</b>을 쓰도록 한 곳에서 만든다 — 제공자가 이 둘을 대조한다. */
    private String redirectUri(OAuthProviderType provider) {
        return appProperties.apiUrl(CALLBACK_PATH_TEMPLATE.formatted(provider.pathValue()));
    }

    private JwtService requireJwt() {
        JwtService jwt = jwtServiceProvider.getIfAvailable();
        if (jwt == null) {
            // dev 스텁으로 도는 로컬 — 소셜 로그인은 쿠키 발급이 전제라 성립하지 않는다.
            throw new OAuthException(ErrorCode.E_AUTH_011.code(), "dev 스텁 환경에서는 소셜 로그인을 쓸 수 없습니다");
        }
        return jwt;
    }
}
