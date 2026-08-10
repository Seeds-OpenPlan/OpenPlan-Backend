package com.openplan.backend.auth.controller;

import com.openplan.backend.auth.dto.LoginRequest;
import com.openplan.backend.auth.dto.SessionInfo;
import com.openplan.backend.auth.dto.TokenRefreshResponse;
import com.openplan.backend.auth.service.AuthService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.AuthCookies;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 인증 컨트롤러 (ST-B1-02 · AUTH-01/07/08 · ACCT-03) — 로그인·세션·로그아웃·토큰 회전 실구현.
 *
 * <p>D-16의 4주차 교체 지점이다. 이 네 EP는 {@link AuthStubController}에서 여기로 승격됐고,
 * 그쪽에는 아직 실구현 전인 것(OAuth·이메일 인증·비밀번호 재설정·재활성화)만 501로 남아 있다.
 * {@code exceptions.md §5-5}가 "E-AUTH-011이 4주차 이후 응답에서 0건"을 검증 항목으로 두고 있어,
 * 나머지도 각 스토리에서 승격되면 스텁 컨트롤러 자체가 사라진다.
 *
 * <p><b>토큰은 본문에 없다.</b> 성공의 부작용은 {@code Set-Cookie}이며(ADR-0001 · R8),
 * 프론트는 토큰을 저장하지도 읽지도 않는다.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth", description = "로그인·세션·로그아웃·토큰 회전 (AUTH-01/07/08 · ACCT-03)")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/sessions")
    @Operation(summary = "로컬 로그인 (AUTH-01)",
            description = "성공 시 op_at(30분)·op_rt(14일) httpOnly 쿠키 발급. "
                    + "자격 불일치 401 E-AUTH-001 · 잠금 401 E-AUTH-002 · 미인증 403 E-AUTH-005 · "
                    + "비활성 409 E-AUTH-008 · 삭제됨 410 E-AUTH-009.")
    public ResponseEntity<ApiResponse<SessionInfo>> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, result.accessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, result.refreshCookie().toString())
                .body(ApiResponse.ok(result.session()));
    }

    @GetMapping("/session")
    @Operation(summary = "세션 확인 (AUTH-07)",
            description = "새로고침 시 로그인 상태 복원. 쿠키가 없거나 무효면 401 E-COM-002.")
    public ApiResponse<SessionInfo> session(@CurrentUser UUID userId) {
        return ApiResponse.ok(authService.currentSession(userId));
    }

    @DeleteMapping("/session")
    @Operation(summary = "로그아웃 (ACCT-03)",
            description = "세션 폐기 + 쿠키 즉시 만료. 쿠키가 없어도 204(멱등).")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
        AuthService.LogoutResult result = authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, result.accessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, result.refreshCookie().toString())
                .build();
    }

    @PostMapping("/token-refresh")
    @Operation(summary = "access 재발급 — refresh 회전 (AUTH-07/08)",
            description = "쿠키의 op_rt로만 동작(본문 없음). 무효·만료·재사용 전부 401 E-AUTH-007이며, "
                    + "재사용이 탐지되면 해당 사용자의 활성 세션을 전부 폐기한다.")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> tokenRefresh(
            @CookieValue(name = AuthCookies.REFRESH, required = false) String refreshToken) {
        AuthService.RefreshResult result = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, result.accessCookie().toString())
                .header(HttpHeaders.SET_COOKIE, result.refreshCookie().toString())
                .body(ApiResponse.ok(result.body()));
    }
}
