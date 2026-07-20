package com.openplan.backend.availability.dto;

import com.openplan.backend.availability.domain.AvailabilityPattern;
import com.openplan.backend.common.Weekday;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/**
 * 가용 시간 패턴 1행 — 요청/응답 공용(openapi {@code AvailabilityPattern} 1:1).
 *
 * <p>요청 시 네 필드 모두 필수(@NotNull) — PUT은 요일 7행 완전체를 받는다. 시간 형식(5분 단위)·
 * 시작&lt;종료의 시맨틱 검증은 서비스에서 E-COM-009로 처리한다(Bean Validation은 존재/형식까지).
 * {@code startTime}/{@code endTime}은 "HH:mm[:ss]" 문자열(JavaTimeModule).
 */
public record AvailabilityPatternDto(
        @NotNull Weekday weekday,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime,
        @NotNull Boolean isActive
) {

    public static AvailabilityPatternDto from(AvailabilityPattern p) {
        return new AvailabilityPatternDto(p.getWeekday(), p.getStartTime(), p.getEndTime(), p.isActive());
    }
}
