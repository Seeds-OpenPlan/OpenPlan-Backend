package com.openplan.backend.onboarding.dto;

/**
 * 온보딩 진행 수정 요청(PATCH /users/me/onboarding-progress). 부분 수정 — 각 플래그는 선택이며
 * {@code null}(미제공)이면 변경하지 않는다(단계별 저장 SQ-07 · 튜토리얼 재실행 리셋 TUT-09 모두 이 형태).
 *
 * <p>{@code tutorialSampleProjectId}는 서버 관리값이라 요청 필드에 포함하지 않는다 — 본문에 들어와도
 * 무시된다(Jackson FAIL_ON_UNKNOWN=off, Spring Boot 기본). 플래그는 boolean이라 별도 형식 검증이 없다.
 */
public record UpdateOnboardingProgressRequest(
        Boolean introDone,
        Boolean profileDone,
        Boolean availabilityDone,
        Boolean fixedScheduleDone,
        Boolean tutorialDone,
        Boolean calendarSyncDone
) {
}
