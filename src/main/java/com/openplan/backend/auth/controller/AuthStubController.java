package com.openplan.backend.auth.controller;

import com.openplan.backend.auth.dto.SessionInfo;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 인증 스텁 컨트롤러 (D-16 · ADR-0010 · api-contracts §2.1).
 *
 * <p>1~3주차 스텁 기간 동작: {@code GET /auth/session}=dev 사용자 200, {@code DELETE /auth/session}=204 no-op,
 * 나머지 인증 EP=501 E-AUTH-011. FE는 세션 가드·로그아웃 배선을 스텁 기간에 완성할 수 있다(§4.1-6).
 *
 * <p>4주차 교체: 각 EP 실구현으로 전환하고 이 스텁 컨트롤러를 제거/승격한다(WEEK4-STUB 소멸, exceptions §5-5).
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth (stub)", description = "D-16 dev-auth 스텁 — 4주차 실구현으로 교체")
public class AuthStubController {

    @GetMapping("/session")
    @Operation(summary = "세션 확인 (AUTH-07) — 스텁: dev 사용자 200")
    public ApiResponse<SessionInfo> session(@CurrentUser UUID userId) {
        return ApiResponse.ok(new SessionInfo(userId, true, true));
    }

    @DeleteMapping("/session")
    @Operation(summary = "로그아웃 (ACCT-03) — 스텁: 204 no-op")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent().build();
    }

    // ---- 나머지 인증 EP: 501 E-AUTH-011 (4주차 실구현 전) ----

    @PostMapping("/sessions")
    @Operation(summary = "로컬 로그인 (AUTH-01) — 4주차")
    public void login() {
        throw notImplemented();
    }

    @PostMapping("/token-refresh")
    @Operation(summary = "access 재발급 (AUTH-07/08) — 4주차")
    public void tokenRefresh() {
        throw notImplemented();
    }

    @GetMapping("/oauth/{provider}")
    @Operation(summary = "소셜 인가 시작 (AUTH-02) — 4주차")
    public void oauthStart(@PathVariable String provider) {
        throw notImplemented();
    }

    @GetMapping("/oauth/{provider}/callback")
    @Operation(summary = "소셜 콜백 (AUTH-02) — 4주차")
    public void oauthCallback(@PathVariable String provider) {
        throw notImplemented();
    }

    @PostMapping("/reactivations")
    @Operation(summary = "계정 재활성화 (ACCT-05) — 4주차")
    public void reactivate() {
        throw notImplemented();
    }

    @PostMapping("/email-verifications")
    @Operation(summary = "인증 메일 발송 (AUTH-04) — 4주차")
    public void emailVerification() {
        throw notImplemented();
    }

    @PostMapping("/email-verifications/confirmation")
    @Operation(summary = "이메일 인증 완료 (AUTH-04) — 4주차")
    public void emailVerificationConfirm() {
        throw notImplemented();
    }

    @PostMapping("/password-resets")
    @Operation(summary = "재설정 메일 요청 (AUTH-05) — 4주차")
    public void passwordResetRequest() {
        throw notImplemented();
    }

    @org.springframework.web.bind.annotation.PatchMapping("/password-resets/{token}")
    @Operation(summary = "재설정 완료 (AUTH-06) — 4주차")
    public void passwordResetConfirm(@PathVariable String token) {
        throw notImplemented();
    }

    private OpenPlanException notImplemented() {
        return new OpenPlanException(ErrorCode.E_AUTH_011);
    }
}
