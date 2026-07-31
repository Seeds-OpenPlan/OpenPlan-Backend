package com.openplan.backend.availability.dto;

import java.util.List;

/**
 * 가용 시간 조회/저장 응답 — openapi {@code AvailabilityView} 1:1.
 *
 * @param patterns           요일 순(MON..SUN) 패턴 목록
 * @param weeklyTotalMinutes 활성 패턴의 (종료-시작) 합(분). 파생 계산값 — DB 컬럼 아님(FIX-01).
 */
public record AvailabilityView(List<AvailabilityPatternDto> patterns, int weeklyTotalMinutes) {
}
