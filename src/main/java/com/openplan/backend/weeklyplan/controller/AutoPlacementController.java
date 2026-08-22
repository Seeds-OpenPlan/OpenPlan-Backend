package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.AutoPlacementRequest;
import com.openplan.backend.weeklyplan.dto.PlacementProposalResponse;
import com.openplan.backend.weeklyplan.service.AutoPlacementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 자동 배치 제안 API (RB-PLAN-01 / SS-05) — {@code /weekly-plans/{planId}/auto-placements}.
 * first-fit 제안만 반환하고 <b>저장하지 않는다</b>(C-2) — 적용은 사용자가 {@code block-batches}로.
 */
@RestController
@RequestMapping("/weekly-plans/{planId}/auto-placements")
@Tag(name = "plan-block", description = "자동 배치 제안 (RB-PLAN-01 · first-fit)")
public class AutoPlacementController {

    private final AutoPlacementService autoPlacementService;

    public AutoPlacementController(AutoPlacementService autoPlacementService) {
        this.autoPlacementService = autoPlacementService;
    }

    @PostMapping
    @Operation(summary = "자동 배치 제안 (SS-05 · first-fit)",
            description = "미배치 태스크를 우선순위·마감일·예상시간 순으로 가용 시간에 first-fit 배치한 초안을 제안한다. "
                    + "taskIds 미지정 시 미배치 전량. 저장하지 않음(적용은 block-batches). 계획 부재·타인 → 404.")
    public ApiResponse<PlacementProposalResponse> propose(
            @CurrentUser UUID userId,
            @PathVariable UUID planId,
            @RequestBody(required = false) AutoPlacementRequest request) {
        return ApiResponse.ok(autoPlacementService.propose(userId, planId, request));
    }
}
