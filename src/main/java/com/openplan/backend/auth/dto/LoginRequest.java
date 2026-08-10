package com.openplan.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로컬 로그인 요청 (ST-B1-02 · AUTH-01) — 정본 openapi {@code login} 요청 스키마
 * ({@code required: [email, password]})와 동일 shape.
 *
 * <p><b>형식 검증을 최소로 둔다.</b> 가입({@code SignUpRequest})과 달리 여기서
 * {@code @Email}·비밀번호 규칙을 걸면, 형식이 어긋난 입력만 400으로 갈라져 나가면서
 * "이 이메일은 형식조차 우리 규칙과 다르다"는 신호를 준다. 로그인 실패는 원인과 무관하게
 * 401 E-AUTH-001 하나로 수렴해야 한다(계정 열거 방지 — AC2).
 *
 * @param email    비어 있지 않기만 하면 된다. 정규화는 서비스가 가입과 같은 규칙으로 수행
 * @param password 원문. 로그 어디에도 남기지 않는다
 */
public record LoginRequest(
        @NotBlank(message = "email is required") String email,
        @NotBlank(message = "password is required") String password) {
}
