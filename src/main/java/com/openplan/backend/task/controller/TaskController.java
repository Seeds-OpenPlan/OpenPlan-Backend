package com.openplan.backend.task.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.task.dto.TaskResponse;
import com.openplan.backend.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
