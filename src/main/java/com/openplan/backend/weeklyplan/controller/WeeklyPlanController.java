package com.openplan.backend.weeklyplan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.weeklyplan.dto.ValidateWeeklyPlanRequest;
import com.openplan.backend.weeklyplan.dto.ValidationReportResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanCreateRequest;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanQuery;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanView;
import com.openplan.backend.weeklyplan.service.WeeklyPlanService;
import com.openplan.backend.weeklyplan.service.WeeklyPlanValidationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
@Tag(name = "weekly-plan", description = "주간 계획 생성·조회·검증·확정 (ST-B2-07·09)")
public class WeeklyPlanController {

    private final WeeklyPlanService weeklyPlanService;
    private final WeeklyPlanValidationService validationService;

    public WeeklyPlanController(WeeklyPlanService weeklyPlanService,
                               WeeklyPlanValidationService validationService) {
        this.weeklyPlanService = weeklyPlanService;
        this.validationService = validationService;
    }

    @PostMapping
    @Operation(summary = "주간 계획 get-or-create (PLAN 진입)",
            description = "weekStartDate로 진입. 없으면 생성(201, weekEndDate=start+6·status=DRAFT), "
                    + "이미 있으면 기존 반환(200). 주차 중복은 오류가 아니다(재호출 멱등). ")
    public ResponseEntity<ApiResponse<WeeklyPlanResponse>> getOrCreate(
            @CurrentUser UUID userId,
            @Valid @RequestBody WeeklyPlanCreateRequest request) {
        WeeklyPlanService.GetOrCreateResult result = weeklyPlanService.getOrCreate(userId, request);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.plan()));
    }

    @GetMapping
    @Operation(summary = "주간 계획 조회 (PLAN-01·02)",
            description = "weekStartDate로 그 주 계획(data.plan) + 캘린더용 블록 목록(data.blocks) 조회. "
                    + "없는 주차 → 200 + data.plan=null(오류 아님).")
    public ApiResponse<WeeklyPlanView> get(
            @CurrentUser UUID userId,
            @Valid @ModelAttribute WeeklyPlanQuery query) {
        return ApiResponse.ok(weeklyPlanService.getByWeek(userId, query.getWeekStartDate()));
    }

    @PostMapping("/{planId}/validations")
    @Operation(summary = "검증 실행 (SS-06 / PLAN-21·22)",
            description = "본문 없음/virtualBlocks 없음 → 저장 초안 판정 + validation_issues 영속. "
                    + "virtualBlocks 있음(빈 배열 포함) → dry-run(무영속). 위반은 오류가 아니다 — 200 ValidationReport.")
    public ApiResponse<ValidationReportResponse> validate(
            @CurrentUser UUID userId,
            @PathVariable UUID planId,
            @RequestBody(required = false) ValidateWeeklyPlanRequest request) {
        return ApiResponse.ok(validationService.validate(userId, planId, request));
    }

    @PostMapping("/{planId}/confirmation")
    @Operation(summary = "주간 계획 확정 (PLAN-03·28)",
            description = "서버가 검증을 재실행해 스스로 판단(요청 본문 없음). 차단(BLOCK) 존재 시 409 E-PLAN-004, "
                    + "없으면 status=CONFIRMED. 경고는 남아도 확정된다. Idempotency-Key 헤더 수용.")
    public ApiResponse<WeeklyPlanResponse> confirm(
            @CurrentUser UUID userId,
            @PathVariable UUID planId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ApiResponse.ok(validationService.confirm(userId, planId, idempotencyKey));
    }
}
