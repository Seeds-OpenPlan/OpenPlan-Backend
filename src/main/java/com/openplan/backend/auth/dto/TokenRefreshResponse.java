package com.openplan.backend.auth.dto;

/**
 * 토큰 재발급 응답 (ST-B1-02 · AUTH-07/08) — 정본 openapi {@code refreshToken} 200 응답
 * ({@code data.previousPath})과 1:1.
 *
 * <p>새 토큰은 본문이 아니라 {@code Set-Cookie}로 나간다(ADR-0001).
 *
 * <p>🔴 <b>{@code previousPath}를 채우는 주체가 계약에 없다.</b> 컬럼
 * ({@code auth_sessions.previous_path})과 이 응답 필드는 정의돼 있는데, 어느 시점에 무엇이 쓰는지는
 * 어느 문서에도 없다 — 로그인 요청에 경로 자리가 없고, {@code token-refresh}는 {@code requestBody}가
 * 아예 없으며, 401이 나는 일반 요청에는 {@code op_rt} 쿠키가 실리지 않아
 * ({@code Path=/api/v1/auth/token-refresh}) 그 시점에 세션을 특정할 수도 없다.
 * 서버는 저장된 값을 <b>그대로 읽어 돌려주기만</b> 하며(없으면 null), 쓰기 주체는 미결로 남긴다.
 */
public record TokenRefreshResponse(String previousPath) {
}
