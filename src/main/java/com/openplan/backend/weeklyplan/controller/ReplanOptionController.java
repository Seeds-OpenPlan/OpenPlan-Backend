package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.GenerateReplanResponse;
import com.openplan.backend.weeklyplan.service.ReplanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 재계획 대안 API (SS-07~09 / RB-PLAN-03·04·05) — {@code /weekly-plans/{planId}/replan-options}.
 * 3전략 대안을 생성·저장하고 기준선(KEEP_CURRENT)과 함께 반환한다. 조회(GET)·적용은 후속 슬라이스.
 */
@RestController
@RequestMapping("/weekly-plans/{planId}/replan-options")
@Tag(name = "weekly-plan", description = "재계획 대안 (RB-PLAN-03·04·05)")
public class ReplanOptionController {

    private final ReplanService replanService;

    public ReplanOptionController(ReplanService replanService) {
        this.replanService = replanService;
    }

    @PostMapping
    @Operation(summary = "재계획 대안 생성 (SS-07~09)",
            description = "3전략(최소 변경·마감 우선·부하 분산) 대안을 생성·저장하고 기준선(KEEP_CURRENT)과 함께 반환. "
                    + "재생성 시 기존 대안 전면 교체. 계획 부재·타인 → 404.")
    public ResponseEntity<ApiResponse<GenerateReplanResponse>> generate(
            @CurrentUser UUID userId,
            @PathVariable UUID planId) {
        GenerateReplanResponse result = replanService.generate(userId, planId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result));
    }
}
