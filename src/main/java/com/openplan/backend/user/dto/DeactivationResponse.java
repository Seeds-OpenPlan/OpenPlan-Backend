package com.openplan.backend.user.dto;

import java.time.Instant;

/**
 * 계정 비활성화 응답 (정본 openapi.yaml {@code deactivateMe} 200) — 복구 기한 하나.
 *
 * <p>이 값이 사용자가 되돌릴 수 있는 마지막 시각이다. 이후에는 배치가 실제로 지운다(NFR-007).
 * 로그인 차단({@code AuthService.assertLoginAllowed})도 같은 값을 본다.
 */
public record DeactivationResponse(Instant recoverableUntil) {
}
