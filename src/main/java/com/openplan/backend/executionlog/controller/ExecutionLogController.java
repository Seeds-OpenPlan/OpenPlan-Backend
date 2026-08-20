package com.openplan.backend.executionlog.controller;

import com.openplan.backend.executionlog.dto.ExecutionLogCreateRequest;
import com.openplan.backend.executionlog.dto.ExecutionLogResponse;
import com.openplan.backend.executionlog.service.ExecutionLogService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 수행 이력 API (PLAN-15 · DASH-05) — {@code /tasks/{taskId}/execution-logs}.
 *
 * <p>{@link com.openplan.backend.task.controller.TaskController}와 루트를 공유하지만 별도 클래스로 둔다 —
 * 수행 이력은 태스크 편집 계열과 다른 도메인(통계 연료)이고, 파일을 나눠야 병렬 작업에서 충돌하지 않는다.
 * {@code /api/v1} 프리픽스는 WebConfig가 부여한다.
 */
@RestController
@RequestMapping("/tasks")
@Tag(name = "task")
public class ExecutionLogController {

    private final ExecutionLogService executionLogService;

    public ExecutionLogController(ExecutionLogService executionLogService) {
        this.executionLogService = executionLogService;
    }

    @PostMapping("/{taskId}/execution-logs")
    @Operation(summary = "실제 시간 기록 (PLAN-15)",
            description = "5분 단위 실제 수행 시간을 기록한다. W5 수행 통계(요약·편차·시간 패턴·보정 제안)가 이 이력을 집계한다. "
                    + "태스크 부재·타인 소유 → 404 E-COM-004(존재 은닉). 값 위반·타인 계획 블록 참조 → 422 E-COM-009. "
                    + "종료된 프로젝트의 태스크도 기록할 수 있다(이미 일어난 일의 기록이므로 D-10 가드 대상 아님).")
    public ResponseEntity<ApiResponse<ExecutionLogResponse>> record(
            @CurrentUser UUID userId,
            @PathVariable UUID taskId,
            @RequestBody ExecutionLogCreateRequest request) {
        ExecutionLogResponse created = executionLogService.record(userId, taskId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }
}
