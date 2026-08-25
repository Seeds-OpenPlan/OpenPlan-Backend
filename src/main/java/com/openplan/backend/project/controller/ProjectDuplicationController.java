package com.openplan.backend.project.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.project.dto.DuplicationPreviewResponse;
import com.openplan.backend.project.service.ProjectDuplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 프로젝트 복제 API (PROJ-10·11·12) — {@code /projects/{projectId}} 하위. 복제 프리뷰·실행을 담당한다.
 * 프로젝트 CRUD({@link ProjectController})·태스크({@code ProjectTaskController})와 관심사를 분리한다.
 * (복제 실행 POST는 후속 슬라이스)
 */
@RestController
@RequestMapping("/projects/{projectId}")
@Tag(name = "projects", description = "프로젝트 복제 (PROJ-10·11·12)")
public class ProjectDuplicationController {

    private final ProjectDuplicationService duplicationService;

    public ProjectDuplicationController(ProjectDuplicationService duplicationService) {
        this.duplicationService = duplicationService;
    }

    @GetMapping("/duplication-preview")
    @Operation(summary = "복제 항목 확인 (PROJ-11)",
            description = "복제 시 딸려 오는 항목 개요(이름·설명·태스크 수·WBS 수) 반환. 주간 계획 배치 항목은 복제되지 않는다. "
                    + "부재·타인 → 404.")
    public ApiResponse<DuplicationPreviewResponse> preview(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        return ApiResponse.ok(duplicationService.preview(userId, projectId));
    }
}
