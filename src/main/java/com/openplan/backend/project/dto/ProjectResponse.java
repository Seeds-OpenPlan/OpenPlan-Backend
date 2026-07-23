package com.openplan.backend.project.dto;

import com.openplan.backend.project.domain.Project;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 프로젝트 응답 (상세·목록 항목·쓰기 응답 공용 — 경계 계약 B-2로 이 필드 전체로 한정).
 * version 포함(편집 낙관락 입력). updated_at은 스키마에 없으므로 응답에도 없다.
 *
 * <p>PROJ-04 태스크 배지는 후속(B-1) — 이 record에 필드 추가하는 후방호환 확장으로 열려 있다.
 */
public record ProjectResponse(
        UUID projectId,
        String name,
        String description,
        LocalDate dueDate,
        String status,
        Integer priority,
        Instant closedAt,
        long version,
        Instant createdAt) {

    public static ProjectResponse from(Project p) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getDueDate(),
                p.getStatus().name(),
                p.getPriority(),
                p.getClosedAt(),
                p.getVersion(),
                p.getCreatedAt());
    }
}
