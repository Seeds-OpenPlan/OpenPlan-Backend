package com.openplan.backend.auth.controller;

import com.openplan.backend.global.mail.MailDispatcher;
import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 이메일 인증·비밀번호 재설정 통합 테스트 (ST-B1-04/05 · 4주차 ②).
 *
 * <p>{@link MailDispatcher}만 대역으로 바꾼다 — 실제 SMTP를 부르면 테스트가 네트워크와 남의 계정에 묶인다.
 * <b>그 바깥은 전부 실제로 돈다</b>: 토큰 발급·해시 저장·링크 조립·DB 상태 전이.
 * 메일 본문에서 링크를 뽑아 그 토큰으로 확정까지 밟으므로, <b>사용자가 실제로 걷는 경로</b>가 그대로 검증된다.
 *
 * <p>검증의 축은 셋이다: ⑴ 계정 존재 여부가 응답으로 드러나지 않는가 ⑵ 링크가 <b>한 번만</b> 통하는가
 * (NFR-005) ⑶ 비밀번호를 바꾸면 <b>기존 세션이 끊기는가</b>(AC4).
 */
@SpringBootTest(properties = {
        "op.auth.dev-stub=false",
        "op.auth.jwt.secret=test-only-secret-value-that-is-long-enough-32"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class EmailAuthApiTest {

    private static final String SEND_VERIFICATION = "/api/v1/auth/email-verifications";
    private static final String CONFIRM_VERIFICATION = "/api/v1/auth/email-verifications/confirmation";
    private static final String REQUEST_RESET = "/api/v1/auth/password-resets";
    private static final String LOGIN = "/api/v1/auth/sessions";

    private static final String DOMAIN = "@emailauthtest.local";
    private static final String UNVERIFIED = "unverified" + DOMAIN;
    private static final String VERIFIED = "verified" + DOMAIN;
    private static final String SOCIAL = "social" + DOMAIN;
    private static final String PASSWORD = "password123";
    private static final String NEW_PASSWORD = "newpassword456";

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private MailDispatcher mailDispatcher;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
        seedLocalUser(UNVERIFIED, false);
        seedLocalUser(VERIFIED, true);
        seedSocialUser(SOCIAL);
    }

    // ------------------------------------------------------- 인증 메일 발송

    @Test
    @DisplayName("미인증 계정 → 202 + 메일 1통, 본문 링크에 토큰이 실린다")
    void sendsVerificationMail() throws Exception {
        mockMvc.perform(json(SEND_VERIFICATION, body(UNVERIFIED)))
                .andExpect(status().isAccepted());

        assertThat(capturedToken()).isNotBlank();
        // 원문은 저장하지 않는다 — DB에는 해시만 있다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_tokens WHERE token_hash = ?",
                Integer.class, capturedToken())).isZero();
    }

    @Test
    @DisplayName("🔴 없는 계정도 202 · 메일은 안 나간다 — 가입 여부가 드러나지 않는다")
    void unknownEmailStillAccepted() throws Exception {
        mockMvc.perform(json(SEND_VERIFICATION, body("nobody" + DOMAIN)))
                .andExpect(status().isAccepted());

        verify(mailDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("이미 인증된 계정도 202 · 메일은 안 나간다 — 보낼 이유가 없다")
    void verifiedAccountSendsNothing() throws Exception {
        mockMvc.perform(json(SEND_VERIFICATION, body(VERIFIED)))
                .andExpect(status().isAccepted());

        verify(mailDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("60초 안에 재요청 → 429 E-COM-007 + details.retryAfterSeconds (ui-spec 카운트다운 재료)")
    void resendIsThrottled() throws Exception {
        mockMvc.perform(json(SEND_VERIFICATION, body(UNVERIFIED))).andExpect(status().isAccepted());

        mockMvc.perform(json(SEND_VERIFICATION, body(UNVERIFIED)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("E-COM-007"))
                .andExpect(jsonPath("$.error.details.retryAfterSeconds").isNumber());
    }

    // ------------------------------------------------------------- 인증 확정

    @Test
    @DisplayName("링크 토큰으로 확정 → 200, 계정이 인증됨으로 바뀐다")
    void confirmVerifiesAccount() throws Exception {
        mockMvc.perform(json(SEND_VERIFICATION, body(UNVERIFIED))).andExpect(status().isAccepted());

        mockMvc.perform(json(CONFIRM_VERIFICATION, """
                {"token":"%s"}""".formatted(capturedToken())))
                .andExpect(status().isOk());

        assertThat(isVerified(UNVERIFIED)).isTrue();
    }

    @Test
    @DisplayName("같은 링크를 두 번 눌러도 200 — 멱등(성공한 동작을 실패로 보이게 하지 않는다)")
    void confirmIsIdempotent() throws Exception {
        mockMvc.perform(json(SEND_VERIFICATION, body(UNVERIFIED))).andExpect(status().isAccepted());
        String token = capturedToken();

        mockMvc.perform(json(CONFIRM_VERIFICATION, """
                {"token":"%s"}""".formatted(token))).andExpect(status().isOk());
        mockMvc.perform(json(CONFIRM_VERIFICATION, """
                {"token":"%s"}""".formatted(token))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("모르는 토큰 → 410 E-AUTH-004")
    void unknownTokenIsGone() throws Exception {
        mockMvc.perform(json(CONFIRM_VERIFICATION, """
                {"token":"not-a-real-token"}"""))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-004"));
    }

    @Test
    @DisplayName("만료된 링크 → 410 E-AUTH-004, 계정은 미인증 그대로")
    void expiredTokenIsGone() throws Exception {
        mockMvc.perform(json(SEND_VERIFICATION, body(UNVERIFIED))).andExpect(status().isAccepted());
        jdbc.update("UPDATE auth_tokens SET expires_at = now() - interval '1 hour'");

        mockMvc.perform(json(CONFIRM_VERIFICATION, """
                {"token":"%s"}""".formatted(capturedToken())))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-004"));

        assertThat(isVerified(UNVERIFIED)).isFalse();
    }

    @Test
    @DisplayName("🔴 인증 전에는 로그인이 막히고, 인증 후에는 열린다 — ②가 여는 문턱")
    void verificationUnlocksLogin() throws Exception {
        mockMvc.perform(json(LOGIN, loginBody(UNVERIFIED, PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-005"));

        mockMvc.perform(json(SEND_VERIFICATION, body(UNVERIFIED))).andExpect(status().isAccepted());
        mockMvc.perform(json(CONFIRM_VERIFICATION, """
                {"token":"%s"}""".formatted(capturedToken()))).andExpect(status().isOk());

        mockMvc.perform(json(LOGIN, loginBody(UNVERIFIED, PASSWORD)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------- 비밀번호 재설정

    @Test
    @DisplayName("재설정 요청 → 202 + 메일 1통")
    void requestsResetMail() throws Exception {
        mockMvc.perform(json(REQUEST_RESET, body(VERIFIED))).andExpect(status().isAccepted());
        assertThat(capturedToken()).isNotBlank();
    }

    @Test
    @DisplayName("🔴 없는 계정·소셜 계정도 똑같이 202 · 메일 없음 — 응답이 갈리지 않는다(AC2)")
    void resetRequestNeverRevealsAccount() throws Exception {
        mockMvc.perform(json(REQUEST_RESET, body("nobody" + DOMAIN))).andExpect(status().isAccepted());
        mockMvc.perform(json(REQUEST_RESET, body(SOCIAL))).andExpect(status().isAccepted());

        verify(mailDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("재설정 완료 → 200, 새 비밀번호로 로그인되고 기존 비밀번호는 막힌다")
    void completeChangesPassword() throws Exception {
        mockMvc.perform(json(REQUEST_RESET, body(VERIFIED))).andExpect(status().isAccepted());

        mockMvc.perform(patch("/api/v1/auth/password-resets/" + capturedToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"%s"}""".formatted(NEW_PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(json(LOGIN, loginBody(VERIFIED, NEW_PASSWORD))).andExpect(status().isOk());
        mockMvc.perform(json(LOGIN, loginBody(VERIFIED, PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-001"));
    }

    @Test
    @DisplayName("🔴 재설정 성공 시 기존 세션이 전부 끊긴다 (AC4)")
    void completeRevokesExistingSessions() throws Exception {
        mockMvc.perform(json(LOGIN, loginBody(VERIFIED, PASSWORD))).andExpect(status().isOk());
        assertThat(activeSessions(VERIFIED)).isEqualTo(1);

        mockMvc.perform(json(REQUEST_RESET, body(VERIFIED))).andExpect(status().isAccepted());
        mockMvc.perform(patch("/api/v1/auth/password-resets/" + capturedToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"%s"}""".formatted(NEW_PASSWORD)))
                .andExpect(status().isOk());

        assertThat(activeSessions(VERIFIED)).isZero();
    }

    @Test
    @DisplayName("재설정 링크는 한 번만 통한다 → 두 번째 410 E-AUTH-006 (NFR-005)")
    void resetLinkIsSingleUse() throws Exception {
        mockMvc.perform(json(REQUEST_RESET, body(VERIFIED))).andExpect(status().isAccepted());
        String token = capturedToken();
        String bodyJson = """
                {"newPassword":"%s"}""".formatted(NEW_PASSWORD);

        mockMvc.perform(patch("/api/v1/auth/password-resets/" + token)
                .contentType(MediaType.APPLICATION_JSON).content(bodyJson)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/auth/password-resets/" + token)
                        .contentType(MediaType.APPLICATION_JSON).content(bodyJson))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("E-AUTH-006"));
    }

    @Test
    @DisplayName("재설정도 비밀번호 규칙을 지킨다 → 400 E-COM-001 (가입에서 막은 값이 여기로 들어오지 않는다)")
    void completeEnforcesPasswordRule() throws Exception {
        mockMvc.perform(json(REQUEST_RESET, body(VERIFIED))).andExpect(status().isAccepted());

        mockMvc.perform(patch("/api/v1/auth/password-resets/" + capturedToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newPassword":"onlyletters"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    // ------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(String email) {
        return """
                {"email":"%s"}""".formatted(email);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}""".formatted(email, password);
    }

    /** 마지막으로 발송된 메일 본문에서 링크의 토큰을 뽑는다 — 사용자가 링크를 누르는 것과 같은 경로. */
    private String capturedToken() {
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailDispatcher, times(1)).send(anyString(), anyString(), bodyCaptor.capture());
        Matcher matcher = TOKEN_IN_LINK.matcher(bodyCaptor.getValue());
        assertThat(matcher.find()).as("메일 본문에 ?token= 링크가 있어야 한다").isTrue();
        return matcher.group(1);
    }

    private boolean isVerified(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_email_verified FROM users WHERE email = ?", Boolean.class, email));
    }

    private int activeSessions(String email) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM auth_sessions s JOIN users u ON u.user_id = s.user_id
                 WHERE u.email = ? AND s.status = 'ACTIVE'
                """, Integer.class, email);
    }

    private void seedLocalUser(String email, boolean verified) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (user_id, email, password_hash, login_type, is_email_verified, status)
                VALUES (?, ?, ?, 'LOCAL', ?, 'ACTIVE')
                """, userId, email, passwordEncoder.encode(PASSWORD), verified);
        seedRelatedRows(userId);
    }

    private void seedSocialUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (user_id, email, login_type, social_provider, social_provider_user_id,
                                   is_email_verified, status)
                VALUES (?, ?, 'SOCIAL', 'GOOGLE', ?, true, 'ACTIVE')
                """, userId, email, "social-" + userId);
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
