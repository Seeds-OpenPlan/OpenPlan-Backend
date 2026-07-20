package com.openplan.backend.availability.controller;

import com.openplan.backend.availability.dto.AvailabilityView;
import com.openplan.backend.availability.dto.SaveAvailabilitiesRequest;
import com.openplan.backend.availability.service.AvailabilityService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 가용 시간 컨트롤러(ST-B1-09 · ONB-04 · FIX-01/02/03 · PLAN-32).
 *
 * <p>PUT은 전체 교체(요일 7행)로 캘린더/설정 어느 표면이든 단일 계약이다(PLAN-32의 저장도 이 PUT).
 * 인증 주체는 {@code @CurrentUser}로 스코핑한다.
 */
@RestController
@RequestMapping("/users/me/availabilities")
@Tag(name = "availability", description = "가용 시간 (ONB-04 · FIX-01/02/03 · PLAN-32)")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @GetMapping
    @Operation(summary = "가용 시간 조회 (FIX-01 · ONB-04) — 요일 7행 + 주간 총합")
    public ApiResponse<AvailabilityView> getAvailabilities(@CurrentUser UUID userId) {
        return ApiResponse.ok(availabilityService.getMyAvailabilities(userId));
    }

    @PutMapping
    @Operation(summary = "가용 시간 전체 저장 (ONB-04 · FIX-02/03 · PLAN-32) — 요일 7행 멱등 교체")
    public ApiResponse<AvailabilityView> saveAvailabilities(@CurrentUser UUID userId,
                                                           @Valid @RequestBody SaveAvailabilitiesRequest request) {
        return ApiResponse.ok(availabilityService.saveAvailabilities(userId, request));
    }
}
