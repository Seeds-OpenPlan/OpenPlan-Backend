package com.openplan.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 비밀번호 변경 요청(PATCH /users/me/password · ACCT-02).
 *
 * <p>재설정(PasswordResetService)과 달리 <b>이미 인증된 주체</b>가 스스로 바꾸는 경로다. 토큰이 아니라
 * 현재 비밀번호로 본인을 재확인한다 — 세션이 탈취된 상태에서 비밀번호까지 바뀌는 것을 막는 유일한 관문이다.
 *
 * <p>{@code newPassword} 하한 8자는 openapi {@code minLength: 8}과 같은 값이다. 상한 72는 BCrypt가
 * 72바이트를 넘는 입력을 조용히 잘라내기 때문에 둔다 — 자르고 저장하면 사용자가 입력한 것과 저장된 것이
 * 달라지고, 그 차이는 로그인 실패로만 드러난다.
 */
public record ChangePasswordRequest(

        @NotBlank(message = "currentPassword must not be blank")
        String currentPassword,

        @NotBlank(message = "newPassword must not be blank")
        @Size(min = 8, max = 72, message = "newPassword must be 8..72 characters")
        String newPassword
) {
}
