package com.openplan.backend.structuring.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.structuring.dto.StructureWarningResponse;
import com.openplan.backend.structuring.service.StructureWarningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 태스크 구조 부족 경고 API (SS-04 / RB-PROJ-02) — {@code /projects/{projectId}/structure-warnings}.
 * 형제 규칙 RB-PROJ-01(구조화 초안)과 같은 패키지 — 둘 다 "프로젝트 구조화" 관심사다.
 * 라우트 루트가 달라 컨트롤러는 별도 클래스로 둔다(루트당 1클래스 관례).
 */
@RestController
@RequestMapping("/projects/{projectId}/structure-warnings")
@Tag(name = "project", description = "태스크 구조 부족 경고 (RB-PROJ-02)")
public class StructureWarningController {

    private final StructureWarningService service;

    public StructureWarningController(StructureWarningService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "태스크 구조 부족 경고 (SS-04 / RB-PROJ-02)",
            description = "계획 전 점검 항목을 규칙으로 판정해 반환한다(읽기 전용·저장 없음). "
                    + "TOO_FEW_TASKS: 태스크 총수(상태 무관) 3건 미만 · "
                    + "MISSING_ESTIMATES: 미완료 태스크 중 예상시간 미입력 1건 이상 · "
                    + "DEADLINE_PRESSURE: IN_PROGRESS이면서 마감까지 0~3일이고 미완료 태스크가 남음. "
                    + "순서는 위 나열 순 고정, 유형당 최대 1건(최대 3건). 경고가 없으면 빈 배열(200 — 오류 아님). "
                    + "CLOSED 프로젝트는 태스크 쓰기가 불가하므로 판정을 억제해 항상 빈 배열, "
                    + "PAUSED는 마감 압박만 보류한다. 조회 전 자동 종료 평가 선행. 부재·타인 → 404.")
    public ApiResponse<List<StructureWarningResponse>> warnings(
            @CurrentUser UUID userId,
            @PathVariable UUID projectId) {
        return ApiResponse.ok(service.warnings(userId, projectId));
    }
}
