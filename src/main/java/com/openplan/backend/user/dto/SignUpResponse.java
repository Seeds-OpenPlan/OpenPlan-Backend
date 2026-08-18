package com.openplan.backend.user.dto;

import java.util.UUID;

/**
 * 회원가입 응답 (ST-B1-04 · AUTH-03) — 정본 openapi {@code signUp} 201 응답 shape.
 *
 * <p><b>쿠키를 발급하지 않는다.</b> 가입은 로그인이 아니다 — 이메일 인증(AUTH-04)을 거쳐야
 * 로그인이 열린다(미인증 로그인은 403 E-AUTH-005). 그래서 {@code emailVerificationRequired}로
 * 프론트에 다음 단계를 알린다.
 */
public record SignUpResponse(UUID userId, boolean emailVerificationRequired) {
}
