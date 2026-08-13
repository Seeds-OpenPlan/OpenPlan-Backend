package com.openplan.backend.fixedschedule.controller;

import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import com.openplan.backend.fixedschedule.dto.FixedScheduleCreateRequest;
import com.openplan.backend.fixedschedule.dto.FixedScheduleResponse;
import com.openplan.backend.fixedschedule.dto.FixedScheduleUpdateRequest;
import com.openplan.backend.fixedschedule.service.FixedScheduleService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 고정 일정 API (FIX-04·05) — {@code /fixed-schedules}. {@code /api/v1}은 WebConfig가 부여.
 * 성공 봉투 {@link ApiResponse}, 오류는 OpenPlanException → GlobalExceptionHandler 단일 창구.
 * (편집·삭제·주차예외·충돌 미리보기는 후속 슬라이스)
 */
@RestController
@RequestMapping("/fixed-schedules")
@Tag(name = "fixed-schedule", description = "고정 일정 CRUD (FIX-04~09)")
public class FixedScheduleController {

    private final FixedScheduleService fixedScheduleService;

    public FixedScheduleController(FixedScheduleService fixedScheduleService) {
        this.fixedScheduleService = fixedScheduleService;
    }

    @PostMapping
    @Operation(summary = "고정 일정 생성 (FIX-05·ONB-06)",
            description = "제목·요일·시작/종료 시각(5분 단위) 입력. 생성 시 source=MANUAL·status=ACTIVE. "
                    + "이후 해당 시간은 주간 계획에서 배치 불가로 반영된다.")
    public ResponseEntity<ApiResponse<FixedScheduleResponse>> create(
            @CurrentUser UUID userId,
            @Valid @RequestBody FixedScheduleCreateRequest request) {
        FixedScheduleResponse created = fixedScheduleService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping
    @Operation(summary = "고정 일정 목록 (FIX-04)",
            description = "내 고정 일정을 weekday·시작 시각 순으로 반환. status(ACTIVE·INACTIVE) 지정 시 필터.")
    public ApiResponse<List<FixedScheduleResponse>> list(
            @CurrentUser UUID userId,
            @RequestParam(required = false) FixedScheduleStatus status) {
        return ApiResponse.ok(fixedScheduleService.list(userId, status));
    }

    @PatchMapping("/{fixedScheduleId}")
    @Operation(summary = "고정 일정 편집 (FIX-06)",
            description = "제목·요일·시각·기간 전체 교체(version 필수). 부재·타인 → 404, 동시 수정 충돌 → 409(latest 동봉), "
                    + "값 오류 → 422. 편집 시 저장된 주간 계획 배치 불가 시간이 갱신된다.")
    public ApiResponse<FixedScheduleResponse> update(
            @CurrentUser UUID userId,
            @PathVariable UUID fixedScheduleId,
            @Valid @RequestBody FixedScheduleUpdateRequest request) {
        return ApiResponse.ok(fixedScheduleService.update(userId, fixedScheduleId, request));
    }
}
