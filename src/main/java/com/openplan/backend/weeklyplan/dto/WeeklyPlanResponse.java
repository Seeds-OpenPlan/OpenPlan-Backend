package com.openplan.backend.weeklyplan.dto;

import com.openplan.backend.weeklyplan.domain.WeeklyPlan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 주간 계획 응답 (생성·조회 공용). 요약(PLAN-01)은 {@code totalPlannedMinutes}(사용시간=블록 합 캐시)와
 * {@code placedBlockCount}(배치 블록 수)까지 포함한다. 가용/여유 시간은 이번 스토리 범위 밖(availability 도메인).
 */
public record WeeklyPlanResponse(
        UUID weeklyPlanId,
        LocalDate weekStartDate,
        LocalDate weekEndDate,
        String status,
        int totalPlannedMinutes,
        long placedBlockCount,
        long version,
        Instant confirmedAt,
        Instant createdAt) {

    public static WeeklyPlanResponse from(WeeklyPlan p, long placedBlockCount) {
        return new WeeklyPlanResponse(
                p.getId(),
                p.getWeekStartDate(),
                p.getWeekEndDate(),
                p.getStatus().name(),
                p.getTotalPlannedMinutes(),
                placedBlockCount,
                p.getVersion(),
                p.getConfirmedAt(),
                p.getCreatedAt());
    }
}
