package com.openplan.backend.stats.dto;

import java.time.LocalDate;

/**
 * {@code StatsSummary} 응답 (openapi 1:1). {@code completionRate}·{@code varianceRate}는 이력 0건이면
 * null(0/0 분모 회피) — {@code empty=true}와 함께 "빈 상태"를 나타낸다(RB-STAT-01 GWT, 오류 아님).
 */
public record StatsSummaryResponse(
        String period,
        LocalDate rangeStart,
        LocalDate rangeEnd,
        int totalEstimatedMinutes,
        int totalActualMinutes,
        Double completionRate,
        Double varianceRate,
        boolean empty) {
}
