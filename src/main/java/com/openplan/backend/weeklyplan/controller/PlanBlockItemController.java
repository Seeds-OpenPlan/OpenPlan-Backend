package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.PlanBlockMoveRequest;
import com.openplan.backend.weeklyplan.dto.PlanBlockResponse;
import com.openplan.backend.weeklyplan.service.PlanBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 계획 블록 단건 API (ST-B2-08 후속) — {@code /plan-blocks/{blockId}}. 배치(생성)는 계획 하위 경로
 * ({@code /weekly-plans/{planId}/blocks}, {@link PlanBlockController})라 컨트롤러를 분리한다 — 정본 openapi가
 * 블록 단건 조작을 {@code /plan-blocks/{blockId}}로 두었다(블록 id만으로 소유 판정 가능). 이동(PATCH)은 후속.
 */
@RestController
@RequestMapping("/plan-blocks")
@Tag(name = "plan-block", description = "주간 계획 블록 해제·이동 (PLAN-16·18·19·20)")
public class PlanBlockItemController {

    private final PlanBlockService planBlockService;

    public PlanBlockItemController(PlanBlockService planBlockService) {
        this.planBlockService = planBlockService;
    }

    @PatchMapping("/{blockId}")
    @Operation(summary = "블록 이동·시간 조정 (PLAN-19·20)",
            description = "부분 수정 — startAt/endAt 시각 조정, targetWeekStartDate로 주차 이동(대상 주 초안 get-or-create). "
                    + "확정 계획이면 DRAFT 복귀·양쪽 주 total 재계산. 시작>=종료 → 422 E-PLAN-002, 5분 단위 위반 → 422 E-COM-009. "
                    + "부재·타인 → 404. 겹침은 막지 않음(검증 엔진 소관).")
    public ApiResponse<PlanBlockResponse> move(
            @CurrentUser UUID userId,
            @PathVariable UUID blockId,
            @RequestBody PlanBlockMoveRequest request) {
        return ApiResponse.ok(planBlockService.moveBlock(userId, blockId, request));
    }

    @DeleteMapping("/{blockId}")
    @Operation(summary = "블록 해제·삭제 (PLAN-16·18 / TUT-07)",
            description = "TASK 블록=배치 해제(태스크 남은 블록 0이면 UNASSIGNED 복귀) / SCHEDULE 블록=일정 연쇄 삭제. "
                    + "가용/여유 재계산. 부재·타인 → 404. 성공 204(봉투 없음).")
    public ResponseEntity<Void> delete(
            @CurrentUser UUID userId,
            @PathVariable UUID blockId) {
        planBlockService.deleteBlock(userId, blockId);
        return ResponseEntity.noContent().build();
    }
}
