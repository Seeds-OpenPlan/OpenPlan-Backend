package com.openplan.backend.weeklyplan.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 재계획 대안 응답 (SS-07~09 / 정본 openapi.yaml {@code ReplanOption}).
 *
 * <p>{@code replanOptionId}는 저장된 대안만 값(기준선 KEEP_CURRENT는 null — 행 미생성). {@code proposedBlocks}는
 * 재배치할 TASK 위치(정본 PlanBlockInput 부분집합 — 전부 TASK). {@code score}는 현재 null(산출식 미정).
 */
public record ReplanOptionResponse(
        UUID replanOptionId,
        String strategyType,
        String changeSummary,
        String recommendationReason,
        BigDecimal score,
        List<ProposedBlock> proposedBlocks,
        boolean isSelected) {

    /** 재배치 블록 1건 — PlanBlockInput 부분집합(재계획은 TASK만). */
    public record ProposedBlock(String blockType, UUID taskId, Instant startAt, Instant endAt) {
    }
}
