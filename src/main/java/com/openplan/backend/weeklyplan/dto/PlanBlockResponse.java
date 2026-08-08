package com.openplan.backend.weeklyplan.dto;

import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.repository.PlanBlockView;

import java.time.Instant;
import java.util.UUID;

/**
 * 블록 응답 (ST-B2-08). 정본 openapi.yaml {@code PlanBlock} shape.
 * task/schedule 중 하나만 non-null(block_type에 따라). {@code title}은 task/schedule 제목 조인 파생,
 * {@code projectId}는 TASK만(SCHEDULE은 null).
 */
public record PlanBlockResponse(
        UUID planBlockId,
        UUID weeklyPlanId,
        String blockType,
        UUID taskId,
        UUID scheduleId,
        String title,
        UUID projectId,
        Instant startAt,
        Instant endAt,
        String status) {

    /** 배치 직후 단건 응답 — title·projectId는 서비스가 확보한 참조에서 넘긴다(별도 조회 불요). */
    public static PlanBlockResponse of(PlanBlock b, String title, UUID projectId) {
        return new PlanBlockResponse(
                b.getId(),
                b.getWeeklyPlanId(),
                b.getBlockType().name(),
                b.getTaskId(),
                b.getScheduleId(),
                title,
                projectId,
                b.getStartAt(),
                b.getEndAt(),
                b.getStatus().name());
    }

    /** 목록 조회 응답 — 조인 뷰(title·projectId 파생 포함)에서 매핑. */
    public static PlanBlockResponse fromView(PlanBlockView v) {
        return new PlanBlockResponse(
                v.getPlanBlockId(),
                v.getWeeklyPlanId(),
                v.getBlockType(),
                v.getTaskId(),
                v.getScheduleId(),
                v.getTitle(),
                v.getProjectId(),
                v.getStartAt(),
                v.getEndAt(),
                v.getStatus());
    }
}
