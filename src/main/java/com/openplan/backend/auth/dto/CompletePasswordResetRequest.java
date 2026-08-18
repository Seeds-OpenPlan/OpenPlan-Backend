package com.openplan.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 재설정 완료 요청 (AUTH-06) — 정본 openapi {@code completePasswordReset}
 * ({@code required: [newPassword]}, {@code minLength: 8})과 일치.
 *
 * <p>규칙은 가입({@code SignUpRequest})과 <b>동일하다</b> — 가입에서 막은 비밀번호가 재설정으로
 * 들어올 수 있으면 규칙이 규칙이 아니게 된다.
 *
 * @param newPassword 8자 이상 + 영문·숫자 포함. 상한 72는 BCrypt가 초과분을 조용히 잘라내기 때문
 */
public record CompletePasswordResetRequest(
        @NotBlank(message = "newPassword is required")
        @Size(min = 8, max = 72, message = "newPassword must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "newPassword must contain both letters and digits")
        String newPassword) {
}
