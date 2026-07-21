package com.openplan.backend.project.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.project.dto.ProjectCreateRequest;
import com.openplan.backend.project.dto.ProjectResponse;
import com.openplan.backend.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 프로젝트 API (ST-B2-01). {@code /api/v1} 프리픽스는 WebConfig가 부여 — 여기선 {@code /projects}만.
 * 성공 봉투 {@link ApiResponse}, 오류는 {@code OpenPlanException} → GlobalExceptionHandler 단일 창구.
 */
@RestController
@RequestMapping("/projects")
@Tag(name = "project", description = "프로젝트 CRUD·상태·자동종료 (ST-B2-01)")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @Operation(summary = "프로젝트 생성 (PROJ-02)")
    public ResponseEntity<ApiResponse<ProjectResponse>> create(
            @CurrentUser UUID userId,
            @Valid @RequestBody ProjectCreateRequest request) {
        ProjectResponse created = projectService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }
}
