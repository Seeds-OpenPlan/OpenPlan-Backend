package com.openplan.backend.weeklyplan.dto;

import com.openplan.backend.weeklyplan.domain.WeeklyPlan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 주간 계획 응답 (생성·조회 공용). 요약(PLAN-01)은 {@code totalPlannedMinutes}(사용시간=블록 합 캐시)와
 * {@code placedBlockCount}, 그리고 조회 시 캘린더 렌더링용 {@code blocks} 목록(start_at 순)을 포함한다.
 * 가용/여유 시간은 이번 범위 밖(availability 도메인).
 */
public record WeeklyPlanResponse(
        UUID weeklyPlanId,
        LocalDate weekStartDate,
        LocalDate weekEndDate,
        String status,
        int totalPlannedMinutes,
        int placedBlockCount,
        long version,
        Instant confirmedAt,
        Instant createdAt,
        List<PlanBlockResponse> blocks) {

    public static WeeklyPlanResponse from(WeeklyPlan p, List<PlanBlockResponse> blocks) {
        return new WeeklyPlanResponse(
                p.getId(),
                p.getWeekStartDate(),
                p.getWeekEndDate(),
                p.getStatus().name(),
                p.getTotalPlannedMinutes(),
                blocks.size(),
                p.getVersion(),
                p.getConfirmedAt(),
                p.getCreatedAt(),
                blocks);
    }
}
