package com.openplan.backend.structuring.dto;

import com.openplan.backend.task.dto.TaskCreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 태스크 일괄 생성 요청 (정본 openapi.yaml {@code createTasksBulk}) — 구조화 초안 채택 경로.
 *
 * <p>{@code draftId} 는 선택이다. 담겨 오면 그 초안을 채택으로 표시한다("무엇이 쓰였나"의 기록).
 * 사용자가 초안을 고쳐 보내도 되고, 초안과 무관한 태스크를 섞어 보내도 된다 — 그때는 draftId 가 없다.
 *
 * <p>🔴 {@code @Valid} 를 리스트와 각 항목 양쪽에 붙인다. 하나만 붙이면 중첩으로 캐스케이드되지 않아
 * 항목의 필수 검증이 통째로 무력화된다(블록 일괄 적용에서 실제로 났던 결함이다).
 */
public record TaskBulkCreateRequest(
        @NotEmpty(message = "tasks는 비어 있을 수 없습니다.") @Valid List<@Valid Item> tasks) {

    /** TaskInput + draftId. 생성 규칙은 단건 생성과 같아 {@link TaskCreateRequest} 로 넘겨 재사용한다. */
    public record Item(
            String title,
            String memo,
            Integer estimatedMinutes,
            Integer priority,
            LocalDate dueDate,
            UUID categoryId,
            UUID draftId) {

        public TaskCreateRequest toCreateRequest() {
            return new TaskCreateRequest(title, memo, estimatedMinutes, priority, dueDate, categoryId, null);
        }
    }
}
