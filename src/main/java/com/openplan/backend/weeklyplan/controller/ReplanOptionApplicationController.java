package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanView;
import com.openplan.backend.weeklyplan.service.ReplanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 재계획 대안 적용 API (PLAN-29 / RB-FIX-01) — {@code /replan-options/{optionId}/application}.
 * 선택한 대안을 초안에 반영한다(is_selected 기록). 대안 id만으로 소유 판정 가능해 계획 하위가 아닌 별도 경로다.
 * <b>초안 반영이며 확정이 아니다</b>(P2).
 */
@RestController
@RequestMapping("/replan-options/{optionId}/application")
@Tag(name = "replan-option", description = "재계획 대안 적용 (PLAN-29)")
public class ReplanOptionApplicationController {

    private final ReplanService replanService;

    public ReplanOptionApplicationController(ReplanService replanService) {
        this.replanService = replanService;
    }

    @PostMapping
    @Operation(summary = "재계획 대안 선택+초안 반영 (PLAN-29)",
            description = "저장된 대안대로 TASK 블록을 재배치하고 이 대안을 선택 표시한다. 확정 아님(DRAFT 유지). "
                    + "반영 후 최신 주간 화면 반환. 대안 부재·타인 → 404.")
    public ApiResponse<WeeklyPlanView> apply(
            @CurrentUser UUID userId,
            @PathVariable UUID optionId) {
        return ApiResponse.ok(replanService.apply(userId, optionId));
    }
}
