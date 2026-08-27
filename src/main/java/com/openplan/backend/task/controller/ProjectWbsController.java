package com.openplan.backend.task.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.task.dto.WbsItemResponse;
import com.openplan.backend.task.service.TaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 프로젝트 WBS 뷰 API (PROJ-13) — {@code /projects/{projectId}/wbs} 루트. 쓰기(PUT wbs-range)는
 * 태스크 스코프라 {@link TaskController}가 소유한다 — 클래스 @RequestMapping이 두 루트를 동시에
 * 못 가져(code-structure §1.1) {@link ProjectTaskController}와도 클래스를 나눈다.
 */
@RestController
@RequestMapping("/projects/{projectId}/wbs")
@Tag(name = "projects", description = "WBS 뷰 (PROJ-13)")
public class ProjectWbsController {

    private final TaskService taskService;

    public ProjectWbsController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Operation(summary = "WBS 뷰 — 태스크별 기간 바 (PROJ-13)",
            description = "프로젝트의 WBS 행 전량 + 태스크 제목. 기간 미설정 태스크는 행이 없어 미포함. "
                    + "정렬 startDate ASC, endDate ASC, wbsItemId ASC 서버 고정. 페이지네이션 없음. "
                    + "평가 불요 — CLOSED/PAUSED 프로젝트도 조회 가능. 부재·타인 projectId → 404.")
    public ApiResponse<List<WbsItemResponse>> list(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        return ApiResponse.ok(taskService.listWbs(userId, projectId));
    }
}
