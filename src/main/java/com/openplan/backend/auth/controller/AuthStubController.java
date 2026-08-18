package com.openplan.backend.auth.controller;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 스텁 컨트롤러 — <b>아직 실구현되지 않은</b> 인증 EP만 501 E-AUTH-011로 응답한다
 * (D-16 · ADR-0010 · api-contracts §2.1).
 *
 * <p><b>4주차 ①·②·③ 이행분이 전부 빠져 마지막 하나만 남았다.</b>
 * 로그인·세션·로그아웃·토큰 회전은 {@link AuthController}, 소셜 인가·콜백은 {@link OAuthController},
 * 이메일 인증·비밀번호 재설정은 {@link EmailAuthController}로 승격됐다.
 *
 * <p>남은 것은 계정 재활성화(ACCT-05) 하나이며 <b>ST-B1-06 소관</b>이다. 그 스토리가 끝나는 시점에
 * 이 클래스는 삭제되고, 그때 비로소 {@code exceptions.md §5-5}의 검증 항목
 * <b>"E-AUTH-011이 4주차 이후 응답에서 0건"</b>이 통과한다.
 */
@RestController
@RequestMapping("/auth")
@Tag(name = "auth (stub)", description = "미구현 인증 EP — ST-B1-06에서 실구현으로 교체")
public class AuthStubController {

    @PostMapping("/reactivations")
    @Operation(summary = "계정 재활성화 (ACCT-05) — ST-B1-06")
    public void reactivate() {
        throw new OpenPlanException(ErrorCode.E_AUTH_011);
    }
}
