package com.openplan.backend.availability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 가용 시간 전체 저장 요청(PUT /users/me/availabilities). 요일 7행 완전체를 받는다(부분 수정 아님).
 *
 * <p>개수(정확히 7)는 여기서 @Size로 강제 → 위반 시 400 E-COM-001. 7요일 완전성·중복 없음과
 * 5분 단위·시작&lt;종료는 서비스가 422 E-COM-009로 검증한다(값 시맨틱).
 */
public record SaveAvailabilitiesRequest(
        @NotNull
        @Size(min = 7, max = 7, message = "patterns must contain exactly 7 items (one per weekday)")
        @Valid
        List<AvailabilityPatternDto> patterns
) {
}
