package com.openplan.backend.weeklyplan.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 자동 배치 제안 응답 (SS-05 / 정본 openapi.yaml {@code PlacementProposal}). <b>저장되지 않은 초안</b>(C-2) —
 * 사용자가 확인·수정 후 {@code block-batches}로 적용한다.
 *
 * <p>{@code proposedBlocks}는 정본 {@code PlanBlockInput} shape(전부 TASK — 자동 배치는 미배치 태스크만 대상).
 * {@code reason}은 정렬 규칙 설명(P4 준수).
 */
public record PlacementProposalResponse(
        List<ProposedBlock> proposedBlocks,
        List<UUID> unplacedTaskIds,
        String reason) {

    /** 제안 블록 1건 — PlanBlockInput 부분집합(자동 배치는 TASK만, schedule 없음). */
    public record ProposedBlock(String blockType, UUID taskId, Instant startAt, Instant endAt) {
    }
}
