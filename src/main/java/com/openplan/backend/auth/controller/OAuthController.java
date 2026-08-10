package com.openplan.backend.auth.controller;

import com.openplan.backend.auth.oauth.OAuthException;
import com.openplan.backend.auth.oauth.OAuthProviderType;
import com.openplan.backend.auth.service.AuthService;
import com.openplan.backend.auth.service.OAuthLoginService;
import com.openplan.backend.global.config.AppProperties;
import com.openplan.backend.global.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 소셜 로그인 컨트롤러 (ST-B1-03 · AUTH-02) — 인가 시작과 콜백.
 *
 * <p><b>이 컨트롤러는 오류 봉투를 쓰지 않는다.</b> 사용자는 브라우저 리다이렉트를 타고 있어 JSON 401을
 * 볼 수 없다. 모든 실패는 {@code /login?error=…} 302로 끝나며, 코드만 쿼리에 싣고
 * <b>토큰·개인정보는 절대 싣지 않는다</b>(ADR-0001 · R8).
 *
 * <p>그래서 {@code GlobalExceptionHandler}에 맡기지 않고 여기서 {@link OAuthException}을 직접 잡는다 —
 * 전역 핸들러가 봉투로 바꿔 버리면 이 계약이 깨진다.
 */
@RestController
@RequestMapping("/auth/oauth")
@Tag(name = "auth-oauth", description = "소셜 로그인 (AUTH-02)")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthLoginService oauthLoginService;
    private final AppProperties appProperties;

    public OAuthController(OAuthLoginService oauthLoginService, AppProperties appProperties) {
        this.oauthLoginService = oauthLoginService;
        this.appProperties = appProperties;
    }

    @GetMapping("/{provider}")
    @Operation(summary = "소셜 인가 시작 (AUTH-02) — 302 제공자",
            description = "서명 state를 발급해 제공자 인가 페이지로 보낸다. 실패 시 /login?error=E-AUTH-010.")
    public ResponseEntity<Void> start(@PathVariable String provider) {
        try {
            OAuthProviderType type = resolve(provider);
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(oauthLoginService.authorizationUrl(type)))
                    .build();
        } catch (Exception e) {
            return failureRedirect(e);
        }
    }

    /**
     * @param error 제공자가 사용자 거부 등을 알릴 때 싣는 값({@code access_denied} 등).
     *              값이 있으면 {@code code}는 오지 않으므로 먼저 걸러야 한다 — AC1의 "거부" 분기다
     */
    @GetMapping("/{provider}/callback")
    @Operation(summary = "소셜 콜백 (AUTH-02) — 쿠키 발급 후 302",
            description = "성공 → /dashboard 또는 /onboarding. 실패 → /login?error=…. "
                    + "쿼리스트링으로 토큰을 전달하지 않는다(ADR-0001).")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        try {
            OAuthProviderType type = resolve(provider);
            if (error != null && !error.isBlank()) {
                // 사용자가 동의 화면에서 거부했거나 제공자가 인가를 거절했다.
                throw new OAuthException(ErrorCode.E_AUTH_010.code(), "제공자 인가 거부: " + error);
            }
            if (code == null || code.isBlank() || state == null || state.isBlank()) {
                throw new OAuthException(ErrorCode.E_AUTH_010.code(), "code 또는 state 누락");
            }

            OAuthLoginService.CallbackResult result = oauthLoginService.handleCallback(type, code, state);
            AuthService.LoginResult session = result.session();

            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(result.redirectUrl()))
                    .header(HttpHeaders.SET_COOKIE, session.accessCookie().toString())
                    .header(HttpHeaders.SET_COOKIE, session.refreshCookie().toString())
                    .build();
        } catch (Exception e) {
            return failureRedirect(e);
        }
    }

    private OAuthProviderType resolve(String provider) {
        return OAuthProviderType.from(provider)
                .orElseThrow(() -> new OAuthException(ErrorCode.E_AUTH_010.code(),
                        "알 수 없는 제공자: " + provider));
    }

    /**
     * 실패 리다이렉트. <b>사유는 로그에만</b> 남기고 사용자에게는 코드만 준다 —
     * 예외 메시지에는 제공자 응답 내용이 섞일 수 있다.
     *
     * <p><b>{@link OAuthException}만 잡으면 계약에 구멍이 남는다.</b> 이 흐름에는 다른 타입도 흘러든다 —
     * {@code AuthService}의 세션 발급은 {@code OpenPlanException}을 던지고(dev 스텁 환경에서 콜백 URL을
     * 직접 치는 경우), 같은 소셜 계정으로 동시에 첫 로그인이 들어오면 UNIQUE 위반이
     * {@code DataIntegrityViolationException}으로 올라온다. 그것들이 전역 핸들러까지 가면
     * <b>브라우저 리다이렉트를 타고 있는 사용자에게 JSON 오류 봉투가 떨어진다.</b>
     *
     * <p>그래서 {@code Exception}까지 받아 전부 302로 떨군다. 대신 <b>예상 못 한 예외는 스택까지 남긴다</b> —
     * 302로 삼켜 버리면 서버 결함이 "소셜 로그인이 가끔 안 돼요"로만 보이고 원인을 찾을 단서가 사라진다.
     */
    private ResponseEntity<Void> failureRedirect(Exception e) {
        String code;
        if (e instanceof OAuthException oauthException) {
            code = oauthException.errorCode();
            log.warn("소셜 로그인 실패: code={} reason={}", code, e.getMessage());
        } else {
            // 계약상 예상하지 못한 경로. 사용자 응답은 같지만 로그 무게가 달라야 한다.
            code = ErrorCode.E_AUTH_010.code();
            log.error("소셜 로그인 중 예기치 못한 예외 — 302로 흡수했으나 원인 확인 필요", e);
        }
        String url = UriComponentsBuilder.fromUriString(appProperties.frontendUrl("/login"))
                .queryParam("error", code)
                .toUriString();
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }
}
