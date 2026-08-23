package com.openplan.backend.structuring.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.structuring.dto.StructuringDraftResponse;
import com.openplan.backend.structuring.service.StructuringDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 태스크 구조화 초안 API (SS-03 / RB-PROJ-01) — {@code /projects/{projectId}/structuring-drafts}.
 * 정적 규칙 사전으로 초안만 만든다(C-1·C-2). 채택은 {@code POST /projects/{projectId}/tasks/bulk}.
 */
@RestController
@RequestMapping("/projects/{projectId}/structuring-drafts")
@Tag(name = "project", description = "태스크 구조화 초안 (RB-PROJ-01)")
public class StructuringDraftController {

    private final StructuringDraftService service;

    public StructuringDraftController(StructuringDraftService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "태스크 구조화 초안 생성 (SS-03)",
            description = "프로젝트 이름을 정적 규칙 사전과 대조해 초안을 제안한다. 저장되는 것은 초안일 뿐이고 "
                    + "태스크는 tasks/bulk 채택 시 생긴다(C-2). 재호출 시 미채택 초안 전면 교체. 부재·타인 → 404.")
    public ResponseEntity<ApiResponse<List<StructuringDraftResponse>>> generate(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.generate(userId, projectId)));
    }
}
