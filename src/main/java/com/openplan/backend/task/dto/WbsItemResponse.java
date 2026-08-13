package com.openplan.backend.task.dto;

import com.openplan.backend.task.domain.WbsItem;

import java.time.LocalDate;
import java.util.UUID;

/**
 * WBS 기간 응답 (정본 openapi.yaml {@code WbsItem} shape). {@code taskTitle}은 별도 조인 없이
 * 서비스가 소유 판정 단계에서 이미 확보한 {@code Task}에서 전달한다(N+1 회피).
 */
public record WbsItemResponse(
        UUID wbsItemId,
        UUID taskId,
        String taskTitle,
        LocalDate startDate,
        LocalDate endDate) {

    public static WbsItemResponse of(WbsItem item, String taskTitle) {
        return new WbsItemResponse(item.getId(), item.getTaskId(), taskTitle, item.getStartDate(), item.getEndDate());
    }
}
