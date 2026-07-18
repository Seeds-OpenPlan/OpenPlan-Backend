package com.openplan.backend.auth.dto;

import java.util.UUID;

/**
 * 세션 확인 응답 (AUTH-07 — 새로고침 시 세션 복원). dev 스텁 기간에는 dev 사용자 기준으로 채운다.
 * 실제 사용자 요약(email·name·온보딩 상태)은 ST-B1-01b(시드) 이후 user 도메인에서 조립한다.
 */
public record SessionInfo(UUID userId, boolean authenticated, boolean onboardingCompleted) {
}
