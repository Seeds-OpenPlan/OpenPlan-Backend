package com.openplan.backend.schedule.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.schedule.dto.ScheduleResponse;
import com.openplan.backend.schedule.dto.ScheduleUpdateRequest;
import com.openplan.backend.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 일정 API (PLAN-17) — {@code /schedules}. {@code /api/v1}은 WebConfig가 부여.
 * 일정은 SCHEDULE 블록 배치 시 생성되며(ST-B2-08), 여기서는 단독 편집만 제공한다.
 */
@RestController
@RequestMapping("/schedules")
@Tag(name = "schedule", description = "일정 편집 (PLAN-17)")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @PatchMapping("/{scheduleId}")
    @Operation(summary = "일정 편집 (PLAN-17)",
            description = "제목·예상시간·우선순위·메모 부분 수정(version 필수). 시각은 편집 대상이 아니다(블록 이동 소관). "
                    + "부재·타인 → 404, 동시 수정 충돌 → 409(latest 동봉), 값 오류 → 422.")
    public ApiResponse<ScheduleResponse> update(
            @CurrentUser UUID userId,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody ScheduleUpdateRequest request) {
        return ApiResponse.ok(scheduleService.update(userId, scheduleId, request));
    }
}
