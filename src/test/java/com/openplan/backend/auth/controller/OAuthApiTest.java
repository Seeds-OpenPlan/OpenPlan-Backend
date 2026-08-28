package com.openplan.backend.auth.controller;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthProviderType;
import com.openplan.backend.auth.oauth.OAuthUserInfo;
import com.openplan.backend.global.security.AuthCookies;
import com.openplan.backend.global.security.JwtService;
import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소셜 로그인 통합 테스트 (ST-B1-03 · AUTH-02).
 *
 * <p>제공자와의 통신({@link OAuthClient})만 대역으로 바꾼다 — 실제 구글·네이버·카카오를 부르면
 * 테스트가 네트워크와 남의 서비스 상태에 묶인다. <b>그 바깥은 전부 실제로 돈다</b>:
 * state 서명·검증, 계정 확정 규칙, DB 쓰기, 쿠키 발급, 리다이렉트 대상.
 *
 * <p>검증의 축은 셋이다: ⑴ 실패는 예외 없이 <b>전부 302 {@code /login?error=…}</b>로 수렴하는가
 * ⑵ <b>쿼리스트링에 토큰이 실리지 않는가</b>(ADR-0001 · R8) ⑶ 같은 이메일의 기존 계정이 있을 때
 * <b>별개 계정을 만들지 않는가</b>(AC2).
 */
@SpringBootTest(properties = {
        "op.auth.dev-stub=false",
        "op.auth.jwt.secret=test-only-secret-value-that-is-long-enough-32",
        "op.oauth.clients.google.client-id=test-google-client",
        "op.oauth.clients.google.client-secret=test-google-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class OAuthApiTest {

    private static final String START = "/api/v1/auth/oauth/google";
    private static final String CALLBACK = "/api/v1/auth/oauth/google/callback";
    private static final String LOGIN_PAGE = "http://localhost:5173/login";

    private static final String DOMAIN = "@oauthapitest.local";
    private static final String SOCIAL_EMAIL = "social" + DOMAIN;
    private static final String LOCAL_EMAIL = "local" + DOMAIN;
    private static final String PROVIDER_USER_ID = "google-user-0001";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private OAuthClient oauthClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
        given(oauthClient.exchangeCodeForAccessToken(any(), any(), any(), any())).willReturn("provider-access-token");
    }

    // ------------------------------------------------------------- 인가 시작

    @Test
    @DisplayName("인가 시작 → 302 제공자, client_id·redirect_uri·서명 state 동반")
    void startRedirectsToProvider() throws Exception {
        MvcResult result = mockMvc.perform(get(START))
                .andExpect(status().isFound())
                .andReturn();

        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith(OAuthProviderType.GOOGLE.authorizationUri());

        // 퍼센트 인코딩 여부에 흔들리지 않도록 디코딩해서 본다 — 검증 대상은 값이지 표기가 아니다.
        String decoded = URLDecoder.decode(location, StandardCharsets.UTF_8);
        assertThat(decoded).contains("client_id=test-google-client");
        assertThat(decoded).contains("response_type=code");
        // 제공자 콘솔 등록값과 같아야 한다 — 다르면 제공자가 인가를 거부한다
        assertThat(decoded).contains("redirect_uri=http://localhost:8080/api/v1/auth/oauth/google/callback");
        assertThat(decoded).contains("scope=openid email profile");
        assertThat(decoded).contains("state=");
    }

    @Test
    @DisplayName("자격증명 없는 제공자(kakao) → 302 /login?error=E-AUTH-010 (기동은 막지 않는다)")
    void unconfiguredProviderFailsGracefully() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/kakao"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    @Test
    @DisplayName("알 수 없는 제공자 → 302 /login?error=E-AUTH-010")
    void unknownProviderFails() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/facebook"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    // -------------------------------------------------------------- 실패 분기

    @Test
    @DisplayName("사용자가 동의를 거부(error=access_denied) → 302 /login?error=E-AUTH-010")
    void providerDenialFails() throws Exception {
        mockMvc.perform(get(CALLBACK).param("error", "access_denied"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    @Test
    @DisplayName("state 누락 → 302 /login?error=E-AUTH-010")
    void missingStateFails() throws Exception {
        mockMvc.perform(get(CALLBACK).param("code", "auth-code"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    @Test
    @DisplayName("위조 state → 302 /login?error=E-AUTH-010 (서명이 대조를 대신한다)")
    void tamperedStateFails() throws Exception {
        mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", validState() + "x"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    @Test
    @DisplayName("🔴 다른 제공자용으로 서명된 state → 거부 (교차 사용 차단)")
    void stateFromAnotherProviderFails() throws Exception {
        String kakaoState = jwtService.issueOAuthState("kakao");

        mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", kakaoState))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    // -------------------------------------------------------------- 성공 흐름

    @Test
    @DisplayName("신규 소셜 계정 → 302 /onboarding + 쿠키 2장, 계정·프로필·온보딩 행 생성")
    void firstSocialLoginCreatesAccount() throws Exception {
        given(oauthClient.fetchUserInfo(any(), any()))
                .willReturn(new OAuthUserInfo(PROVIDER_USER_ID, SOCIAL_EMAIL));

        MvcResult result = mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", validState()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/onboarding"))
                .andReturn();

        assertThat(result.getResponse().getCookie(AuthCookies.ACCESS)).isNotNull();
        assertThat(result.getResponse().getCookie(AuthCookies.REFRESH)).isNotNull();
        // 🔴 쿼리스트링으로 토큰을 흘리지 않는다 (R8)
        assertThat(result.getResponse().getHeader("Location")).doesNotContain("token");

        UUID userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", UUID.class, SOCIAL_EMAIL);
        assertThat(jdbc.queryForObject(
                "SELECT login_type FROM users WHERE user_id = ?", String.class, userId)).isEqualTo("SOCIAL");
        assertThat(jdbc.queryForObject(
                "SELECT is_email_verified FROM users WHERE user_id = ?", Boolean.class, userId)).isTrue();
        assertThat(count("user_profiles", userId)).isEqualTo(1);
        assertThat(count("onboarding_progress", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 온보딩을 마친 계정 → 302 홈(/) — 프론트에 /dashboard 라우트가 없다")
    void completedOnboardingLandsOnHome() throws Exception {
        given(oauthClient.fetchUserInfo(any(), any()))
                .willReturn(new OAuthUserInfo(PROVIDER_USER_ID, SOCIAL_EMAIL));

        // 1) 첫 로그인 — 계정과 온보딩 행이 생기고, 아직 미완료라 /onboarding 으로 간다.
        mockMvc.perform(get(CALLBACK).param("code", "c1").param("state", validState()))
                .andExpect(header().string("Location", "http://localhost:5173/onboarding"));

        // 2) 마법사 4단계를 완료로 — OnboardingProgress.isOnboardingCompleted() 의 정의 그대로.
        UUID userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", UUID.class, SOCIAL_EMAIL);
        jdbc.update("UPDATE onboarding_progress SET profile_done = true, availability_done = true,"
                + " fixed_schedule_done = true, calendar_sync_done = true WHERE user_id = ?", userId);

        // 3) 재로그인 — 여기가 결함 자리였다. "/dashboard" 는 프론트 라우터에 없어 404 화면이 떴다.
        mockMvc.perform(get(CALLBACK).param("code", "c2").param("state", validState()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5173/"));
    }

    @Test
    @DisplayName("재로그인은 같은 계정을 쓴다 — 제공자 측 ID가 신원 기준")
    void repeatedLoginReusesAccount() throws Exception {
        given(oauthClient.fetchUserInfo(any(), any()))
                .willReturn(new OAuthUserInfo(PROVIDER_USER_ID, SOCIAL_EMAIL));

        mockMvc.perform(get(CALLBACK).param("code", "c1").param("state", validState()))
                .andExpect(status().isFound());
        mockMvc.perform(get(CALLBACK).param("code", "c2").param("state", validState()))
                .andExpect(status().isFound());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email LIKE ?", Integer.class, "%" + DOMAIN)).isEqualTo(1);
    }

    @Test
    @DisplayName("제공자가 이메일을 안 주면 계정을 만들지 않는다 — 카카오 선택 동의 케이스")
    void missingEmailDoesNotCreateAccount() throws Exception {
        given(oauthClient.fetchUserInfo(any(), any()))
                .willReturn(new OAuthUserInfo(PROVIDER_USER_ID, null));

        mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", validState()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email LIKE ?", Integer.class, "%" + DOMAIN)).isZero();
    }

    @Test
    @DisplayName("🔴 같은 이메일의 로컬 계정이 있으면 별개 계정을 만들지 않는다 → E-AUTH-003 (AC2)")
    void existingLocalAccountBlocksSocialSignUp() throws Exception {
        seedLocalUser(LOCAL_EMAIL);
        given(oauthClient.fetchUserInfo(any(), any()))
                .willReturn(new OAuthUserInfo(PROVIDER_USER_ID, LOCAL_EMAIL));

        mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", validState()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-003"));

        // 계정이 하나도 늘지 않았고, 기존 계정이 소셜로 바뀌지도 않았다
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email LIKE ?", Integer.class, "%" + DOMAIN)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT login_type FROM users WHERE email = ?", String.class, LOCAL_EMAIL)).isEqualTo("LOCAL");
    }

    @Test
    @DisplayName("잠금 계정은 소셜로도 들어오지 못한다 → E-AUTH-002")
    void lockedAccountCannotLogIn() throws Exception {
        seedSocialUser(SOCIAL_EMAIL, PROVIDER_USER_ID, "LOCKED");
        given(oauthClient.fetchUserInfo(any(), any()))
                .willReturn(new OAuthUserInfo(PROVIDER_USER_ID, SOCIAL_EMAIL));

        mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", validState()))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-002"));
    }

    // ------------------------------------------------------------- helpers

    private String validState() {
        return jwtService.issueOAuthState("google");
    }

    private int count(String table, UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private void seedLocalUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (user_id, email, password_hash, login_type, is_email_verified, status)
                VALUES (?, ?, ?, 'LOCAL', true, 'ACTIVE')
                """, userId, email, passwordEncoder.encode("password123"));
        seedRelatedRows(userId);
    }

    private void seedSocialUser(String email, String providerUserId, String status) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (user_id, email, login_type, social_provider, social_provider_user_id,
                                   is_email_verified, status)
                VALUES (?, ?, 'SOCIAL', 'GOOGLE', ?, true, ?)
                """, userId, email, providerUserId, status);
        seedRelatedRows(userId);
    }

    private void seedRelatedRows(UUID userId) {
        jdbc.update("""
                INSERT INTO user_profiles (profile_id, user_id, name, purpose, timezone, week_start_day)
                VALUES (?, ?, '테스트', NULL, 'Asia/Seoul', 'MON')
                """, UUID.randomUUID(), userId);
        jdbc.update("INSERT INTO onboarding_progress (user_id) VALUES (?)", userId);
    }
}
