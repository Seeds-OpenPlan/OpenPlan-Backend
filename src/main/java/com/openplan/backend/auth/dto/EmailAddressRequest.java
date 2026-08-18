package com.openplan.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 이메일 한 필드만 받는 요청 — 인증 메일 발송(AUTH-04)과 재설정 메일 요청(AUTH-05)이 같은 shape다.
 * 정본 openapi {@code sendEmailVerification}·{@code requestPasswordReset} 요청 스키마
 * ({@code required: [email]})와 일치.
 *
 * @param email 수신 주소. 정규화는 서비스가 가입·로그인과 같은 규칙으로 수행한다
 */
public record EmailAddressRequest(
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email) {
}
