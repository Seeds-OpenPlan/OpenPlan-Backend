package com.openplan.backend.project.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.response.PageMeta;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.project.dto.ProjectCreateRequest;
import com.openplan.backend.project.dto.ProjectResponse;
import com.openplan.backend.project.dto.ProjectUpdateRequest;
import com.openplan.backend.project.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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

    @GetMapping
    @Operation(summary = "프로젝트 목록 조회 (PROJ-01)",
            description = "status=진행중 그룹(IN_PROGRESS,PAUSED)/종료(CLOSED) 쉼표 다중값. 정렬 createdAt DESC 고정. 조회 시 자동종료 평가 선행.")
    public ApiResponse<List<ProjectResponse>> list(
            @CurrentUser UUID userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) List<String> status) {
        Page<ProjectResponse> result = projectService.list(userId, page, size, status);
        return ApiResponse.ok(result.getContent(), PageMeta.from(result));
    }

    @GetMapping("/{projectId}")
    @Operation(summary = "프로젝트 상세 (PROJ-03)", description = "전 필드 반환. 조회 시 자동종료 평가 선행. 부재·타인 소유 → 404.")
    public ApiResponse<ProjectResponse> detail(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        return ApiResponse.ok(projectService.detail(userId, projectId));
    }

    @PutMapping("/{projectId}")
    @Operation(summary = "프로젝트 편집 (PROJ-06)",
            description = "name·description·dueDate·priority 전체 교체 + version 낙관락. status/closedAt 포함 시 400. CLOSED 편집 → 422.")
    public ApiResponse<ProjectResponse> update(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId,
            @Valid @RequestBody ProjectUpdateRequest request) {
        return ApiResponse.ok(projectService.update(userId, projectId, request));
    }

    @DeleteMapping("/{projectId}")
    @Operation(summary = "프로젝트 삭제 (PROJ-09)",
            description = "hard delete. 연관 태스크·WBS·계획블록 함께 제거(FK cascade). 상태 무관. 부재·타인·재삭제 → 404.")
    public ResponseEntity<Void> delete(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        projectService.delete(userId, projectId);
        return ResponseEntity.noContent().build();
    }
}
