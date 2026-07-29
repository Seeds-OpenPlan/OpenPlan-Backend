package com.openplan.backend.category.controller;

import com.openplan.backend.category.dto.TaskCategoryCreateRequest;
import com.openplan.backend.category.dto.TaskCategoryResponse;
import com.openplan.backend.category.service.TaskCategoryService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 태스크 카테고리 API (ST-B2-04 / SC-01) — {@code /task-categories}. {@code /api/v1}은 WebConfig가 부여.
 * 성공 봉투 {@link ApiResponse}, 오류는 OpenPlanException → GlobalExceptionHandler 단일 창구.
 * (목록 GET·삭제 DELETE는 후속 슬라이스에서 추가)
 */
@RestController
@RequestMapping("/task-categories")
@Tag(name = "task-category", description = "태스크 카테고리 프리셋 CRUD (ST-B2-04)")
public class TaskCategoryController {

    private final TaskCategoryService taskCategoryService;

    public TaskCategoryController(TaskCategoryService taskCategoryService) {
        this.taskCategoryService = taskCategoryService;
    }

    @PostMapping
    @Operation(summary = "카테고리 생성 (SC-01)",
            description = "name 1~50자. 사용자 내 이름 중복 → 409 E-CAT-001. 생성 시 sort_order=0.")
    public ResponseEntity<ApiResponse<TaskCategoryResponse>> create(
            @CurrentUser UUID userId,
            @Valid @RequestBody TaskCategoryCreateRequest request) {
        TaskCategoryResponse created = taskCategoryService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping
    @Operation(summary = "카테고리 목록 (SC-01)",
            description = "내 카테고리 전체를 sort_order ASC, name ASC로 반환. 페이지네이션 없음.")
    public ApiResponse<List<TaskCategoryResponse>> list(@CurrentUser UUID userId) {
        return ApiResponse.ok(taskCategoryService.list(userId));
    }
}
