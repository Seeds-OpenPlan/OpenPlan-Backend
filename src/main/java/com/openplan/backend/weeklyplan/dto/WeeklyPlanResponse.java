package com.openplan.backend.weeklyplan.dto;

import com.openplan.backend.weeklyplan.domain.WeeklyPlan;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 주간 계획 단건 (정본 openapi.yaml {@code WeeklyPlan} shape). POST(get-or-create) 응답이자
 * GET 응답 {@link WeeklyPlanView#plan()} 자리에 들어가는 요약. 캘린더 렌더링용 블록 목록은
 * 이 안이 아니라 {@link WeeklyPlanView#blocks()}(GET) 최상위에 둔다.
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
        Instant createdAt) {

    public static WeeklyPlanResponse from(WeeklyPlan p, int placedBlockCount) {
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
