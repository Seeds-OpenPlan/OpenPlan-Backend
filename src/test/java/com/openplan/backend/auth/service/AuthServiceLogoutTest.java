package com.openplan.backend.auth.service;

import com.openplan.backend.auth.repository.AuthSessionRepository;
import com.openplan.backend.global.security.AuthCookies;
import com.openplan.backend.global.security.JwtService;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.onboarding.repository.OnboardingProgressRepository;
import com.openplan.backend.user.repository.UserProfileRepository;
import com.openplan.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 로그아웃 단위 테스트(DB 불요 — 협력자 전량 목킹).
 *
 * <p><b>이 테스트가 있는 이유:</b> {@code AuthApiTest}는 {@code op.auth.dev-stub=false}로 뜬다.
 * 그래서 로그아웃 테스트가 이미 2건 있었는데도 <b>스텁 경로를 아무도 지나지 않았고</b>,
 * 스텁 로컬에서 로그아웃이 501 E-AUTH-011을 내는 것을 잡지 못했다(PR #22 리뷰에서 발견).
 * 여기서는 {@link AuthCookies}·{@link JwtService} 빈이 <b>없는</b> 상태를 직접 만들어 그 경로를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceLogoutTest {

    @Mock private UserRepository userRepository;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private OnboardingProgressRepository onboardingProgressRepository;
    @Mock private AuthSessionRepository authSessionRepository;
    @Mock private AuthSessionTerminator terminator;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ObjectProvider<JwtService> jwtServiceProvider;
    @Mock private ObjectProvider<AuthCookies> authCookiesProvider;
    @Mock private UserClock clock;
    @Mock private AuthCookies cookies;

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, userProfileRepository, onboardingProgressRepository,
                authSessionRepository, terminator, passwordEncoder,
                jwtServiceProvider, authCookiesProvider, clock);
    }

    @Test
    @DisplayName("dev 스텁(쿠키 빈 없음)에서도 로그아웃은 성공한다 — 501이 아니라 쿠키 없는 204")
    void logoutSucceedsWithoutCookieBean() {
        when(jwtServiceProvider.getIfAvailable()).thenReturn(null);
        when(authCookiesProvider.getIfAvailable()).thenReturn(null);

        AuthService.LogoutResult result = service.logout("아무-토큰");

        assertThat(result.accessCookie()).isNull();
        assertThat(result.refreshCookie()).isNull();
        // 토큰을 해시할 JwtService 가 없으므로 세션 조회 자체를 시도하지 않는다.
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    @DisplayName("운영(쿠키 빈 있음)에서는 만료 쿠키 2장을 그대로 싣는다")
    void logoutClearsCookiesWhenBeanPresent() {
        ResponseCookie clearedAccess = ResponseCookie.from(AuthCookies.ACCESS, "").maxAge(0).build();
        ResponseCookie clearedRefresh = ResponseCookie.from(AuthCookies.REFRESH, "").maxAge(0).build();
        when(jwtServiceProvider.getIfAvailable()).thenReturn(null);
        when(authCookiesProvider.getIfAvailable()).thenReturn(cookies);
        when(cookies.clearAccess()).thenReturn(clearedAccess);
        when(cookies.clearRefresh()).thenReturn(clearedRefresh);

        AuthService.LogoutResult result = service.logout(null);

        assertThat(result.accessCookie()).isSameAs(clearedAccess);
        assertThat(result.refreshCookie()).isSameAs(clearedRefresh);
    }
}
