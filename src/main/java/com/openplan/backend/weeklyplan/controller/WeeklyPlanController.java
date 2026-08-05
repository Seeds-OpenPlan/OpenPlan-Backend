package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanCreateRequest;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanQuery;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanResponse;
import com.openplan.backend.weeklyplan.service.WeeklyPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 주간 계획 API (ST-B2-07) — {@code /weekly-plans}. {@code /api/v1}은 WebConfig가 부여.
 * 블록 쓰기({@code /{id}/blocks})는 ST-B2-08, 검증·확정({@code /{id}/validations}·{@code /{id}/confirmation})은
 * ST-B2-09에서 추가된다.
 */
@RestController
@RequestMapping("/weekly-plans")
@Tag(name = "weekly-plan", description = "주간 계획 생성·조회 (ST-B2-07)")
public class WeeklyPlanController {

    private final WeeklyPlanService weeklyPlanService;

    public WeeklyPlanController(WeeklyPlanService weeklyPlanService) {
        this.weeklyPlanService = weeklyPlanService;
    }

    @PostMapping
    @Operation(summary = "주간 계획 생성 (PLAN 진입)",
            description = "weekStartDate로 생성. weekEndDate=start+6일, status=DRAFT. 같은 주차 존재 시 409 E-PLAN-001.")
    public ResponseEntity<ApiResponse<WeeklyPlanResponse>> create(
            @CurrentUser UUID userId,
            @Valid @RequestBody WeeklyPlanCreateRequest request) {
        WeeklyPlanResponse created = weeklyPlanService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping
    @Operation(summary = "주간 계획 조회 (PLAN-01·02)",
            description = "weekStartDate로 그 주 계획 + 요약(사용시간·배치 블록 수) 조회. 없는 주차 → 200 + 빈 응답(data 없음).")
    public ApiResponse<WeeklyPlanResponse> get(
            @CurrentUser UUID userId,
            @Valid @ModelAttribute WeeklyPlanQuery query) {
        return ApiResponse.ok(weeklyPlanService.getByWeek(userId, query.getWeekStartDate()));
    }
}
