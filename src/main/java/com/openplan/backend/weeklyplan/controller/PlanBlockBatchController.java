package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.BlockBatchRequest;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanView;
import com.openplan.backend.weeklyplan.service.PlanBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 블록 일괄 적용 API (RB-PLAN-01·PLAN-29) — {@code /weekly-plans/{planId}/block-batches}.
 * 낱개 블록 배치({@code /blocks}, {@link PlanBlockController})의 형제 경로라 컨트롤러를 분리한다.
 * 제안(자동배치)의 수용 = 사용자 확정 행위(P2). 적용 후 최신 {@link WeeklyPlanView}를 반환한다.
 */
@RestController
@RequestMapping("/weekly-plans/{planId}/block-batches")
@Tag(name = "plan-block", description = "블록 일괄 적용 (RB-PLAN-01·PLAN-29)")
public class PlanBlockBatchController {

    private final PlanBlockService planBlockService;

    public PlanBlockBatchController(PlanBlockService planBlockService) {
        this.planBlockService = planBlockService;
    }

    @PostMapping
    @Operation(summary = "블록 일괄 적용 (RB-PLAN-01·PLAN-29)",
            description = "operations(CREATE·MOVE·DELETE)를 순서대로 한 트랜잭션에서 실행(하나라도 실패 시 전체 롤백). "
                    + "적용 후 최신 블록 목록 + 요약 반환. 계획 부재·타인 → 404, op별 필수 필드 누락 → 422.")
    public ApiResponse<WeeklyPlanView> apply(
            @CurrentUser UUID userId,
            @PathVariable UUID planId,
            @Valid @RequestBody BlockBatchRequest request) {
        return ApiResponse.ok(planBlockService.applyBatch(userId, planId, request));
    }
}
