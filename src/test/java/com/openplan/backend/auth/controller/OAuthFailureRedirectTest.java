package com.openplan.backend.auth.controller;

import com.openplan.backend.auth.service.OAuthLoginService;
import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 소셜 로그인 <b>실패 표면의 계약</b> 검증 — "이 흐름의 모든 실패는 302다"가 참인지 본다.
 *
 * <p>{@link OAuthApiTest}가 정상 경로와 예상된 실패(state 위조·거부·이메일 없음)를 다루는 반면,
 * 여기서는 <b>예상하지 못한 예외</b>가 흘러들 때를 본다. 서비스 자체를 대역으로 세워야 그 상황을
 * 만들 수 있어 클래스를 나눴다(같은 클래스에서 서비스를 mock 하면 나머지 테스트가 전부 무의미해진다).
 *
 * <p><b>왜 이 테스트가 필요한가.</b> 처음 구현은 {@code OAuthException}만 잡았고, 그래서
 * {@code AuthService}가 던지는 {@code OpenPlanException}이나 동시 가입의 UNIQUE 위반이
 * 전역 핸들러까지 올라가 <b>브라우저 리다이렉트 도중에 JSON 오류 봉투</b>가 떨어졌다.
 * 계약을 문장으로만 적어 두면 이렇게 새므로 테스트로 고정한다.
 */
@SpringBootTest(properties = {
        "op.auth.dev-stub=false",
        "op.auth.jwt.secret=test-only-secret-value-that-is-long-enough-32"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class OAuthFailureRedirectTest {

    private static final String START = "/api/v1/auth/oauth/google";
    private static final String CALLBACK = "/api/v1/auth/oauth/google/callback";
    private static final String LOGIN_PAGE = "http://localhost:5173/login";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OAuthLoginService oauthLoginService;

    @Test
    @DisplayName("🔴 콜백 중 예상 못 한 예외 → JSON 봉투가 아니라 302 /login?error=E-AUTH-010")
    void unexpectedExceptionStillRedirects() throws Exception {
        willThrow(new IllegalStateException("예상하지 못한 서버 결함"))
                .given(oauthLoginService).handleCallback(any(), anyString(), anyString());

        mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", "any-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    @Test
    @DisplayName("동시 최초 소셜 가입의 UNIQUE 위반도 302로 흡수된다")
    void dataIntegrityViolationStillRedirects() throws Exception {
        willThrow(new DataIntegrityViolationException("ux_users_social_identity 위반"))
                .given(oauthLoginService).handleCallback(any(), anyString(), anyString());

        mockMvc.perform(get(CALLBACK).param("code", "auth-code").param("state", "any-state"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }

    @Test
    @DisplayName("인가 시작 중 예상 못 한 예외도 302로 흡수된다")
    void unexpectedExceptionOnStartStillRedirects() throws Exception {
        given(oauthLoginService.authorizationUrl(any()))
                .willThrow(new IllegalStateException("예상하지 못한 서버 결함"));

        mockMvc.perform(get(START))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", LOGIN_PAGE + "?error=E-AUTH-010"));
    }
}
