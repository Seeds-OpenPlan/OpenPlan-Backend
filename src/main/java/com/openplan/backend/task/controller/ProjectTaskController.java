package com.openplan.backend.task.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.task.dto.TaskCreateRequest;
import com.openplan.backend.task.dto.TaskResponse;
import com.openplan.backend.task.service.TaskService;
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

import java.util.UUID;

/**
 * 프로젝트 하위 태스크 API (ST-B2-03) — {@code /projects/{projectId}/tasks} 루트(EP-1 목록·EP-2 생성).
 * {@code /api/v1} 프리픽스는 WebConfig가 부여. 성공 봉투 {@link ApiResponse}, 오류는 OpenPlanException →
 * GlobalExceptionHandler 단일 창구. (목록 EP-1은 후속 슬라이스에서 추가)
 */
@RestController
@RequestMapping("/projects/{projectId}/tasks")
@Tag(name = "task", description = "태스크 CRUD·완료 전환·미배치 조회 (ST-B2-03)")
public class ProjectTaskController {

    private final TaskService taskService;

    public ProjectTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    @Operation(summary = "태스크 생성 (PROJ-17)",
            description = "title만 required · 생성 status=UNASSIGNED(version=0). 요청에 status 포함 시 400. "
                    + "과거 dueDate 허용(D-11). 평가 선행 후 CLOSED 프로젝트 → 422 E-PROJ-003, PAUSED 허용. "
                    + "부재·타인 projectId/categoryId → 404.")
    public ResponseEntity<ApiResponse<TaskResponse>> create(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @Valid @RequestBody TaskCreateRequest request) {
        TaskResponse created = taskService.create(userId, projectId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }
}
