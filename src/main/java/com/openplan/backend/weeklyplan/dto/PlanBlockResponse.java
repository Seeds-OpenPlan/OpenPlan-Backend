package com.openplan.backend.weeklyplan.dto;

import com.openplan.backend.weeklyplan.domain.PlanBlock;

import java.time.Instant;
import java.util.UUID;

/**
 * 블록 응답 (ST-B2-08). 생성된 블록의 식별·시각·상태. task/schedule 중 하나만 non-null(block_type에 따라).
 */
public record PlanBlockResponse(
        UUID planBlockId,
        UUID weeklyPlanId,
        UUID taskId,
        UUID scheduleId,
        String blockType,
        Instant startAt,
        Instant endAt,
        String status,
        Instant createdAt) {

    public static PlanBlockResponse from(PlanBlock b) {
        return new PlanBlockResponse(
                b.getId(),
                b.getWeeklyPlanId(),
                b.getTaskId(),
                b.getScheduleId(),
                b.getBlockType().name(),
                b.getStartAt(),
                b.getEndAt(),
                b.getStatus().name(),
                b.getCreatedAt());
    }
}
