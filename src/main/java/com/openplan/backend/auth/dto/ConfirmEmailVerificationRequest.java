package com.openplan.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 이메일 인증 확정 요청 (AUTH-04) — 정본 openapi {@code confirmEmailVerification}
 * ({@code required: [token]})과 일치.
 *
 * <p>프론트가 {@code /verify-email?token=…}에서 읽어 본문으로 실어 보낸다
 * ({@code VerifyEmailPage.jsx} — 실측). 토큰은 <b>본문으로</b> 오간다 — 쿼리스트링에 두면
 * 서버 접근 로그·리퍼러에 그대로 남는다.
 *
 * @param token 메일 링크에 실린 원문 토큰. 서버는 해시로만 대조한다
 */
public record ConfirmEmailVerificationRequest(
        @NotBlank(message = "token is required") String token) {
}
