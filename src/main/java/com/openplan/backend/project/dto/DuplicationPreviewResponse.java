package com.openplan.backend.project.dto;

/**
 * 복제 프리뷰 응답 (PROJ-11 / 정본 openapi.yaml getDuplicationPreview). 복제 시 딸려 오는 항목 개요.
 *
 * <p>{@code note}는 사용자에게 미리 알리는 안내다 — 주간 계획 항목(블록)은 복제되지 않아, 복제본 태스크는
 * 전량 미배치로 생성된다(정본 note description). 저장 없는 조회.
 */
public record DuplicationPreviewResponse(
        String name,
        String description,
        long taskCount,
        long wbsItemCount,
        String note) {
}
