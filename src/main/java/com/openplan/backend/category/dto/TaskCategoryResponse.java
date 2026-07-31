package com.openplan.backend.category.dto;

import com.openplan.backend.category.domain.TaskCategory;

import java.time.Instant;
import java.util.UUID;

/**
 * 카테고리 응답 (생성·목록 항목 공용). updated_at·version은 스키마에 없으므로 응답에도 없다.
 */
public record TaskCategoryResponse(
        UUID taskCategoryId,
        String name,
        int sortOrder,
        Instant createdAt) {

    public static TaskCategoryResponse from(TaskCategory c) {
        return new TaskCategoryResponse(c.getId(), c.getName(), c.getSortOrder(), c.getCreatedAt());
    }
}