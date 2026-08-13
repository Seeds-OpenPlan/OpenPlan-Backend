package com.openplan.backend.fixedschedule.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 주차 한정 비활성화 요청 (PLAN-33). {@code weekStartDate}는 사용자 주 시작 요일 기준 주 시작일(필수, 누락 → 400).
 */
public record WeekExceptionCreateRequest(
        @NotNull(message = "weekStartDate은 필수입니다.") LocalDate weekStartDate) {
}
