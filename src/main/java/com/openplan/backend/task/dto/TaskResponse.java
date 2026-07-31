package com.openplan.backend.task.dto;

import com.openplan.backend.task.domain.Task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 태스크 응답 (상세·목록 항목·쓰기 응답 공용 — AC-R-1 확정). version 포함(편집 낙관락 입력).
 * updated_at은 스키마에 없으므로 응답에도 없다(변경 감지는 version 담당 — data-model §2).
 *
 * <p>미배치 응답은 {@code UnassignedTaskResponse}가 이 필드 + projectName으로 확장한다(TB-7 열림).
 */
public record TaskResponse(
        UUID taskId,
        UUID projectId,
        UUID categoryId,
        String title,
        String memo,
        Integer estimatedMinutes,
        Integer priority,
        LocalDate dueDate,
        String status,
        long version,
        Instant createdAt) {

    public static TaskResponse from(Task t) {
        return new TaskResponse(
                t.getId(),
                t.getProjectId(),
                t.getCategoryId(),
                t.getTitle(),
                t.getMemo(),
                t.getEstimatedMinutes(),
                t.getPriority(),
                t.getDueDate(),
                t.getStatus().name(),
                t.getVersion(),
                t.getCreatedAt());
    }
}
