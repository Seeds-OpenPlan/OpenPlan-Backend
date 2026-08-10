package com.openplan.backend.auth.controller;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 스텁 컨트롤러 — <b>아직 실구현되지 않은</b> 인증 EP만 501 E-AUTH-011로 응답한다
 * (D-16 · ADR-0010 · api-contracts §2.1).
 *
 * <p><b>4주차 ① 이행분은 여기서 빠졌다.</b> 로그인·세션 확인·로그아웃·토큰 회전 4개는
 * {@link AuthController}로 승격됐다. 남은 것은 각 스토리에서 차례로 나간다 —
 * 이메일 인증·비밀번호 재설정은 ST-B1-04/05(②), OAuth 2건은 ST-B1-03(③),
 * 재활성화는 ST-B1-06.
 *
 * <p>{@code exceptions.md §5-5}의 검증 항목이 <b>"E-AUTH-011이 4주차 이후 응답에서 0건"</b>이므로,
 * 남은 EP가 전부 승격되는 시점에 <b>이 클래스는 삭제된다.</b> 스텁이 남아 있는 동안은
 * 그 검증이 아직 통과할 수 없다는 뜻이며, 그것이 남은 작업량의 지표다.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth (stub)", description = "미구현 인증 EP — 각 스토리에서 실구현으로 교체")
public class AuthStubController {

    @GetMapping("/oauth/{provider}")
    @Operation(summary = "소셜 인가 시작 (AUTH-02) — ST-B1-03")
    public void oauthStart(@PathVariable String provider) {
        throw notImplemented();
    }

    @GetMapping("/oauth/{provider}/callback")
    @Operation(summary = "소셜 콜백 (AUTH-02) — ST-B1-03")
    public void oauthCallback(@PathVariable String provider) {
        throw notImplemented();
    }

    @PostMapping("/reactivations")
    @Operation(summary = "계정 재활성화 (ACCT-05) — ST-B1-06")
    public void reactivate() {
        throw notImplemented();
    }

    @PostMapping("/email-verifications")
    @Operation(summary = "인증 메일 발송 (AUTH-04) — ST-B1-04 ②")
    public void emailVerification() {
        throw notImplemented();
    }

    @PostMapping("/email-verifications/confirmation")
    @Operation(summary = "이메일 인증 완료 (AUTH-04) — ST-B1-04 ②")
    public void emailVerificationConfirm() {
        throw notImplemented();
    }

    @PostMapping("/password-resets")
    @Operation(summary = "재설정 메일 요청 (AUTH-05) — ST-B1-05")
    public void passwordResetRequest() {
        throw notImplemented();
    }

    @PatchMapping("/password-resets/{token}")
    @Operation(summary = "재설정 완료 (AUTH-06) — ST-B1-05")
    public void passwordResetConfirm(@PathVariable String token) {
        throw notImplemented();
    }

    private OpenPlanException notImplemented() {
        return new OpenPlanException(ErrorCode.E_AUTH_011);
    }
}
