package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.service.PlanBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
