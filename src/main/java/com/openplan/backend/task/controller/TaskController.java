package com.openplan.backend.task.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.task.dto.TaskResponse;
import com.openplan.backend.task.dto.TaskStatusToggleRequest;
import com.openplan.backend.task.dto.TaskUpdateRequest;
import com.openplan.backend.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 태스크 API (ST-B2-03) — {@code /tasks} 루트(EP-3 단건·EP-4 편집·EP-5 토글·EP-6 삭제·EP-7 미배치).
 * {@code /projects/{id}/tasks} 루트(EP-1·2)는 {@link ProjectTaskController}가 소유한다
 * (클래스 @RequestMapping이 두 루트를 동시에 못 가짐 — code-structure §1.1).
 * {@code /api/v1} 프리픽스는 WebConfig가 부여. (편집/토글/삭제/미배치는 후속 슬라이스에서 추가)
 */
@RestController
@RequestMapping("/tasks")
@Tag(name = "task")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/{taskId}")
    @Operation(summary = "태스크 단건 조회 (PROJ-18 편집 폼 로딩)",
            description = "전 필드 + version 반환(편집 낙관락 입력). 평가 불요. 부재·타인 소유 → 404.")
    public ApiResponse<TaskResponse> detail(
            @CurrentUser UUID userId,
            @PathVariable UUID taskId) {
        return ApiResponse.ok(taskService.detail(userId, taskId));
    }

    @PatchMapping("/{taskId}")
    @Operation(summary = "태스크 편집 단일 폼 (PROJ-18=PLAN-10)",
            description = "6필드(title·memo·estimatedMinutes·priority·dueDate·categoryId) 전체 폼 교체 + version 낙관락. "
                    + "categoryId null=해제. status 포함 시 400(상태는 /status 전용). 평가 선행 후 CLOSED 프로젝트 태스크 "
                    + "→ 422 E-PROJ-003(COMPLETED 태스크·PAUSED 프로젝트는 성공). stale version → 409 + details.latest.")
    public ApiResponse<TaskResponse> update(
            @CurrentUser UUID userId,
            @PathVariable UUID taskId,
            @Valid @RequestBody TaskUpdateRequest request) {
        return ApiResponse.ok(taskService.update(userId, taskId, request));
    }

    @PatchMapping("/{taskId}/status")
    @Operation(summary = "완료/미완료 전환 (PLAN-13/14)",
            description = "{completed, version}. completed=true=완료로 표시(IN_PROGRESS→COMPLETED, 블록 COMPLETED 미러). "
                    + "false=미완료로 되돌리기(블록≥1→IN_PROGRESS+SCHEDULED 미러 / 블록0→UNASSIGNED 착지). "
                    + "UNASSIGNED에 완료 → 422 E-PROJ-003(TT-6). 동일 완료여부=200 no-op(version 검사 전 단락). "
                    + "평가 선행 후 CLOSED 프로젝트 → 422(no-op보다 먼저). stale version → 409 + details.latest.")
    public ApiResponse<TaskResponse> toggleCompletion(
            @CurrentUser UUID userId,
            @PathVariable UUID taskId,
            @Valid @RequestBody TaskStatusToggleRequest request) {
        return ApiResponse.ok(taskService.toggleCompletion(userId, taskId, request));
    }
}
