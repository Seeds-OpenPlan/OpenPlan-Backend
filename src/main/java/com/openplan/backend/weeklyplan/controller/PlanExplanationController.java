package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.PlanExplanationResponse;
import com.openplan.backend.weeklyplan.service.PlanExplanationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 계획 설명 생성 API — {@code /weekly-plans/{planId}/explanation}. 본문 없음 — 현재 블록 +
 * 영속된 검증 이슈로 설명을 만든다({@link PlanExplanationService}). 저장 효과 없음(조회성 생성).
 *
 * <p>{@link AutoPlacementController}와 달리 <b>폴백이 없다</b> — AI가 안 설정됐거나 호출이
 * 실패하면 503(E-PLAN-005)을 그대로 돌린다(가짜 설명을 지어내지 않는다).
 */
@RestController
@RequestMapping("/weekly-plans/{planId}/explanation")
@Tag(name = "plan-block", description = "계획 설명 생성 — 현재 배치 + 검증 이슈를 사람이 읽을 문장으로")
public class PlanExplanationController {

    private final PlanExplanationService explanationService;

    public PlanExplanationController(PlanExplanationService explanationService) {
        this.explanationService = explanationService;
    }

    @PostMapping
    @Operation(summary = "계획 설명 생성",
            description = "현재 블록 배치 + 영속된 검증 이슈(reason)를 설명 접합에 넘겨 한국어 설명을 받는다. "
                    + "본문 없음. 계획 부재·타인 → 404. 설명 접합 미설정/호출 실패·타임아웃 → 503 E-PLAN-005(대체 없음).")
    public ApiResponse<PlanExplanationResponse> explain(
            @CurrentUser UUID userId,
            @PathVariable UUID planId) {
        return ApiResponse.ok(explanationService.explain(userId, planId));
    }
}
