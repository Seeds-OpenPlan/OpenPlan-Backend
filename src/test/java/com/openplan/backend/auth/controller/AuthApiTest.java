package com.openplan.backend.auth.controller;

import com.openplan.backend.global.security.AuthCookies;
import com.openplan.backend.support.TestcontainersConfig;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증 5개 EP 통합 테스트 (ST-B1-02/04 · 4주차 ①).
 *
 * <p>{@code op.auth.dev-stub=false}로 띄운다 — dev 스텁이 켜져 있으면 모든 요청이 고정 사용자로
 * 통과해 로그인 자체가 의미를 잃는다. 즉 이 클래스는 <b>운영과 같은 필터 구성</b>에서 돈다
 * ({@code JwtCookieAuthFilterTest}와 같은 이유·같은 방식).
 *
 * <p>검증의 축은 셋이다: ⑴ 계약 shape(쿠키·상태코드·봉투) ⑵ <b>계정 열거 방지</b>(원인이 달라도 같은 코드)
 * ⑶ <b>refresh 재사용 탐지</b>(훔친 토큰이 다시 오면 그 사용자의 세션이 전부 끊긴다).
 */
@SpringBootTest(properties = {
        "op.auth.dev-stub=false",
        "op.auth.jwt.secret=test-only-secret-value-that-is-long-enough-32"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class AuthApiTest {

    private static final String SIGNUP = "/api/v1/users";
    private static final String LOGIN = "/api/v1/auth/sessions";
    private static final String SESSION = "/api/v1/auth/session";
    private static final String TOKEN_REFRESH = "/api/v1/auth/token-refresh";

    /** 이 클래스 전용 이메일 대역 — 정리도 이 접미사로만 한다(시드 사용자 보호). */
    private static final String DOMAIN = "@authapitest.local";
    private static final String VERIFIED = "verified" + DOMAIN;
    private static final String UNVERIFIED = "unverified" + DOMAIN;
    private static final String LOCKED = "locked" + DOMAIN;
    private static final String FRESH = "fresh" + DOMAIN;
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        // users 삭제 → 프로필·온보딩·세션은 FK CASCADE로 함께 사라진다.
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
        seedUser(VERIFIED, true, "ACTIVE");
        seedUser(UNVERIFIED, false, "ACTIVE");
        seedUser(LOCKED, true, "LOCKED");
    }

    // ------------------------------------------------------------ 회원가입

    @Test
    @DisplayName("가입 → 201 + userId + emailVerificationRequired=true (쿠키 없음 — 가입은 로그인이 아니다)")
    void signUpCreatesAccountWithoutSession() throws Exception {
        MvcResult result = mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody(FRESH, PASSWORD, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.emailVerificationRequired").value(true))
                .andReturn();

        assertThat(result.getResponse().getCookie(AuthCookies.ACCESS)).isNull();
        assertThat(result.getResponse().getCookie(AuthCookies.REFRESH)).isNull();
    }

    @Test
    @DisplayName("가입은 프로필·온보딩 행까지 함께 만든다 — 없으면 온보딩이 시작되지 않는다")
    void signUpCreatesProfileAndOnboardingRows() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody(FRESH, PASSWORD, true)))
                .andExpect(status().isCreated());

        UUID userId = jdbc.queryForObject("SELECT user_id FROM users WHERE email = ?", UUID.class, FRESH);
        assertThat(count("user_profiles", userId)).isEqualTo(1);
        assertThat(count("onboarding_progress", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("중복 이메일 → 409 E-AUTH-003")
    void duplicateEmailIsConflict() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody(VERIFIED, PASSWORD, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-003"));
    }

    @Test
    @DisplayName("대소문자만 다른 이메일도 중복이다 — 정규화하지 않으면 별개 계정이 된다")
    void emailIsCaseInsensitiveOnSignUp() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody(VERIFIED.toUpperCase(), PASSWORD, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-003"));
    }

    @Test
    @DisplayName("비밀번호 규칙 위반(숫자 없음) → 400 E-COM-001")
    void weakPasswordIsRejected() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody(FRESH, "onlyletters", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("약관 미동의 → 400 E-COM-001 (동의 없이 만들어진 계정이 남지 않는다)")
    void termsMustBeAgreed() throws Exception {
        mockMvc.perform(post(SIGNUP)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signUpBody(FRESH, PASSWORD, false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, FRESH))
                .isZero();
    }

    // --------------------------------------------------------------- 로그인

    @Test
    @DisplayName("로그인 성공 → 200 + SessionInfo + op_at·op_rt 쿠키(둘 다 httpOnly)")
    void loginIssuesCookies() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(VERIFIED, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(VERIFIED))
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.weekStartDay").value("MON"))
                // 토큰은 본문에 실리지 않는다 (ADR-0001 · R8)
                .andExpect(jsonPath("$.data.accessToken").doesNotExist())
                .andReturn();

        Cookie access = result.getResponse().getCookie(AuthCookies.ACCESS);
        Cookie refresh = result.getResponse().getCookie(AuthCookies.REFRESH);
        assertThat(access).isNotNull();
        assertThat(access.isHttpOnly()).isTrue();
        assertThat(refresh).isNotNull();
        assertThat(refresh.isHttpOnly()).isTrue();
        assertThat(refresh.getPath()).isEqualTo("/api/v1/auth/token-refresh");
    }

    @Test
    @DisplayName("🔴 없는 계정과 틀린 비밀번호가 같은 401 E-AUTH-001 — 계정 열거 방지")
    void wrongCredentialsAreIndistinguishable() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(VERIFIED, "wrongpassword1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-001"));

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody" + DOMAIN, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-001"));
    }

    @Test
    @DisplayName("이메일 미인증 계정 → 403 E-AUTH-005 + details.resendAvailable")
    void unverifiedAccountCannotLogIn() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(UNVERIFIED, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-005"))
                .andExpect(jsonPath("$.error.details.resendAvailable").value(true));
    }

    @Test
    @DisplayName("잠금 계정 → 401 E-AUTH-002")
    void lockedAccountCannotLogIn() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(LOCKED, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-002"));
    }

    @Test
    @DisplayName("🔴 비밀번호가 틀리면 계정 상태를 드러내지 않는다 — 잠금 계정도 E-AUTH-001")
    void statusIsNotRevealedBeforeCredentialCheck() throws Exception {
        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(LOCKED, "wrongpassword1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-001"));
    }

    // ---------------------------------------------------- 세션 확인·로그아웃

    @Test
    @DisplayName("로그인 쿠키로 GET /auth/session → 200 (새로고침 시 세션 복원)")
    void sessionIsRestoredWithCookie() throws Exception {
        Cookie access = login().getResponse().getCookie(AuthCookies.ACCESS);

        mockMvc.perform(get(SESSION).cookie(access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(VERIFIED))
                .andExpect(jsonPath("$.data.onboardingCompleted").value(false));
    }

    @Test
    @DisplayName("쿠키 없이 GET /auth/session → 401 E-COM-002")
    void sessionRequiresCookie() throws Exception {
        mockMvc.perform(get(SESSION))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-COM-002"));
    }

    @Test
    @DisplayName("로그아웃 → 204 + 쿠키 즉시 만료(Max-Age=0) + 세션 REVOKED")
    void logoutRevokesSessionAndClearsCookies() throws Exception {
        MvcResult loggedIn = login();
        Cookie access = loggedIn.getResponse().getCookie(AuthCookies.ACCESS);
        Cookie refresh = loggedIn.getResponse().getCookie(AuthCookies.REFRESH);

        MvcResult result = mockMvc.perform(delete(SESSION).cookie(access, refresh))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getCookie(AuthCookies.ACCESS).getMaxAge()).isZero();
        assertThat(result.getResponse().getCookie(AuthCookies.REFRESH).getMaxAge()).isZero();
        assertThat(activeSessionCount(VERIFIED)).isZero();
    }

    @Test
    @DisplayName("쿠키 없는 로그아웃도 204 — 멱등이며 토큰 존재 여부를 알려 주지 않는다")
    void logoutIsIdempotent() throws Exception {
        mockMvc.perform(delete(SESSION).cookie(login().getResponse().getCookie(AuthCookies.ACCESS)))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------ 토큰 회전

    @Test
    @DisplayName("refresh 회전 → 200 + 새 쿠키 발급, 이전 refresh는 소진된다")
    void refreshRotatesTokens() throws Exception {
        Cookie oldRefresh = login().getResponse().getCookie(AuthCookies.REFRESH);

        MvcResult rotated = mockMvc.perform(post(TOKEN_REFRESH).cookie(oldRefresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previousPath").doesNotExist())
                .andReturn();

        Cookie newRefresh = rotated.getResponse().getCookie(AuthCookies.REFRESH);
        assertThat(newRefresh).isNotNull();
        assertThat(newRefresh.getValue()).isNotEqualTo(oldRefresh.getValue());
        assertThat(rotated.getResponse().getCookie(AuthCookies.ACCESS)).isNotNull();
    }

    @Test
    @DisplayName("refresh 쿠키 없음 → 401 E-AUTH-007")
    void refreshRequiresCookie() throws Exception {
        mockMvc.perform(post(TOKEN_REFRESH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-007"));
    }

    @Test
    @DisplayName("🔴 소진된 refresh 재사용 → 401 E-AUTH-007 + 해당 사용자의 활성 세션 전부 폐기")
    void refreshReuseRevokesEverySession() throws Exception {
        Cookie stolen = login().getResponse().getCookie(AuthCookies.REFRESH);

        // 정상 회전 — stolen 은 여기서 소진(EXPIRED)된다. 회전 결과로 새 활성 세션 1개가 생긴다.
        mockMvc.perform(post(TOKEN_REFRESH).cookie(stolen))
                .andExpect(status().isOk());
        assertThat(activeSessionCount(VERIFIED)).isEqualTo(1);

        // 훔친 토큰이 뒤늦게 도착 — 정상 클라이언트는 이런 요청을 보내지 않는다.
        mockMvc.perform(post(TOKEN_REFRESH).cookie(stolen))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-007"));

        // 진짜 사용자도 함께 튕겨 나온다 — 조용히 공유되는 것보다 낫다(ADR-0001).
        assertThat(activeSessionCount(VERIFIED)).isZero();
    }

    // ------------------------------------------------------------- helpers

    private MvcResult login() throws Exception {
        return mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(VERIFIED, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String signUpBody(String email, String password, boolean termsAgreed) {
        return """
                {"email":"%s","password":"%s","termsAgreed":%s}""".formatted(email, password, termsAgreed);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    private void seedUser(String email, boolean emailVerified, String status) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (user_id, email, password_hash, login_type, is_email_verified, status)
                VALUES (?, ?, ?, 'LOCAL', ?, ?)
                """, userId, email, passwordEncoder.encode(PASSWORD), emailVerified, status);
        jdbc.update("""
                INSERT INTO user_profiles (profile_id, user_id, name, purpose, timezone, week_start_day)
                VALUES (?, ?, '테스트', NULL, 'Asia/Seoul', 'MON')
                """, UUID.randomUUID(), userId);
        jdbc.update("INSERT INTO onboarding_progress (user_id) VALUES (?)", userId);
    }

    private int count(String table, UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, userId);
    }

    private int activeSessionCount(String email) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM auth_sessions s JOIN users u ON u.user_id = s.user_id
                 WHERE u.email = ? AND s.status = 'ACTIVE'
                """, Integer.class, email);
    }
}
