package com.openplan.backend.structuring.dto;

import com.openplan.backend.structuring.domain.TaskStructuringDraft;

import java.util.UUID;

/**
 * 구조화 초안 1건 (정본 openapi.yaml {@code StructuringDraft}).
 *
 * <p>{@code reason} 은 매칭된 사전 항목을 옮긴 문구다(C-3) — 규칙 기반이라 매 초안이 같은 문구를
 * 공유한다. 응답마다 실어 보내는 것은 계약이 초안 단위로 정의했기 때문이다.
 */
public record StructuringDraftResponse(
        UUID draftId,
        String title,
        Integer proposedEstimatedMinutes,
        Integer proposedPriority,
        String reason) {

    public static StructuringDraftResponse of(TaskStructuringDraft d, String reason) {
        return new StructuringDraftResponse(d.getId(), d.getTitle(),
                d.getProposedEstimatedMinutes(), d.getProposedPriority(), reason);
    }
}
