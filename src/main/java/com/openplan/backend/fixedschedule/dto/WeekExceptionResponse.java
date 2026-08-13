package com.openplan.backend.fixedschedule.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 주차 한정 예외 응답 (PLAN-33) — 정본 openapi.yaml 201 data shape {fixedScheduleId, weekStartDate}.
 */
public record WeekExceptionResponse(
        UUID fixedScheduleId,
        LocalDate weekStartDate) {
}
