package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.PlanBlockCreateRequest;
import com.openplan.backend.weeklyplan.dto.PlanBlockResponse;
import com.openplan.backend.weeklyplan.service.PlanBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 계획 블록 API (ST-B2-08) — {@code /weekly-plans/{planId}/blocks}. {@code /api/v1}은 WebConfig가 부여.
 * (블록 해제·이동·목록은 후속 슬라이스)
 */
@RestController
@RequestMapping("/weekly-plans/{planId}/blocks")
@Tag(name = "plan-block", description = "주간 계획 블록 배치 (ST-B2-08)")
public class PlanBlockController {

    private final PlanBlockService planBlockService;

    public PlanBlockController(PlanBlockService planBlockService) {
        this.planBlockService = planBlockService;
    }

    @PostMapping
    @Operation(summary = "블록 배치 (PLAN-06·07)",
            description = "TASK 블록을 주간계획에 배치. 미배치 태스크→진행중 전이 + 주 total 재계산(같은 tx). "
                    + "확정 계획이면 DRAFT로 복귀. 겹침은 쓰기에서 막지 않음(검증 엔진 소관). 완료 태스크 배치 → 422.")
    public ResponseEntity<ApiResponse<PlanBlockResponse>> create(
            @CurrentUser UUID userId,
            @PathVariable UUID planId,
            @Valid @RequestBody PlanBlockCreateRequest request) {
        PlanBlockResponse created = planBlockService.createBlock(userId, planId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }
}
