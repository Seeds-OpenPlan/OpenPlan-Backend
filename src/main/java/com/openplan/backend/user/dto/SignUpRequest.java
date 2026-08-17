package com.openplan.backend.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 (ST-B1-04 · AUTH-03). 정본 openapi {@code signUp} 요청 스키마와 동일 shape —
 * {@code required: [email, password, termsAgreed]}.
 *
 * <p><b>{@code name}은 받지 않는다.</b> 서버 계약에 자리가 없고, 이름은 온보딩 ONB-02
 * ({@code PATCH /users/me/profile})에서 받는다. 프론트도 PR #32에서 이 계약으로 맞췄다 —
 * 가입 시점에 이름을 받아도 이메일 인증이 앱 밖(메일 클라이언트) 하드 네비게이션이라
 * 온보딩까지 이어붙일 수단이 없다.
 *
 * <p>검증 실패는 전부 400 {@code E-COM-001} + {@code details.fields}로 나간다
 * ({@code GlobalExceptionHandler}) — ST-B1-04 AC2가 요구하는 코드다.
 *
 * @param email       가입 이메일. 정규화(소문자·trim)는 서비스가 하며 중복은 409 E-AUTH-003
 * @param password    8자 이상 + 영문·숫자 포함(ui-spec §AUTH 인라인 규칙과 동일 — AC2).
 *                    상한 72는 BCrypt의 실제 한계다 — BCrypt는 72바이트 초과분을 <b>조용히 잘라내므로</b>,
 *                    막지 않으면 앞 72바이트가 같은 서로 다른 비밀번호가 동일하게 취급된다
 * @param termsAgreed 약관 동의. {@code @AssertTrue}라 false면 가입이 성립하지 않는다 —
 *                    동의 없이 만들어진 계정이 남지 않도록 프론트 체크박스와 이중으로 막는다
 */
public record SignUpRequest(

        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255, message = "email must be at most 255 characters")
        String email,

        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$",
                message = "password must contain both letters and digits")
        String password,

        @NotNull(message = "termsAgreed is required")
        @AssertTrue(message = "termsAgreed must be true")
        Boolean termsAgreed) {
}
