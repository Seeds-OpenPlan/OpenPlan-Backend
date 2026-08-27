package com.openplan.backend.schedule.dto;

import com.openplan.backend.schedule.domain.Schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * 일정 응답 (PLAN-17) — 정본 openapi.yaml {@code Schedule} shape. version 동봉(낙관락 다음 편집·409 재료).
 */
public record ScheduleResponse(
        UUID scheduleId,
        String title,
        Instant startAt,
        Instant endAt,
        Integer estimatedMinutes,
        Integer priority,
        String memo,
        long version) {

    public static ScheduleResponse from(Schedule s) {
        return new ScheduleResponse(
                s.getId(),
                s.getTitle(),
                s.getStartAt(),
                s.getEndAt(),
                s.getEstimatedMinutes(),
                s.getPriority(),
                s.getMemo(),
                s.getVersion());
    }
}
