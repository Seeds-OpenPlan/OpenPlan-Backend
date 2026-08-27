package com.openplan.backend.structuring.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.structuring.dto.TaskBulkCreateRequest;
import com.openplan.backend.structuring.service.TaskBulkService;
import com.openplan.backend.task.dto.TaskResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 태스크 일괄 생성 API (RB-PROJ-01) — {@code /projects/{projectId}/tasks/bulk}.
 * 구조화 초안({@code structuring-drafts})을 사용자가 수정한 뒤 채택하는 자리다.
 */
@RestController
@RequestMapping("/projects/{projectId}/tasks/bulk")
@Tag(name = "task", description = "태스크 일괄 생성 (구조화 초안 채택)")
public class TaskBulkController {

    private final TaskBulkService service;

    public TaskBulkController(TaskBulkService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "태스크 일괄 생성 (구조화 초안 채택)",
            description = "tasks 를 순서대로 한 트랜잭션에서 생성한다(하나라도 실패 시 전체 롤백). "
                    + "draftId 를 담으면 그 초안을 채택 표시한다. 생성 규칙은 단건 생성과 동일 — "
                    + "CLOSED 프로젝트 → 422, 부재·타인 projectId/categoryId → 404.")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> createAll(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @Valid @RequestBody TaskBulkCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createAll(userId, projectId, request)));
    }
}
