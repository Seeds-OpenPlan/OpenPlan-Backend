package com.openplan.backend.weeklyplan.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * 주간 계획 생성 요청 (ST-B2-07). weekStartDate만 받는다 — weekEndDate는 서버가 start+6일로 계산한다.
 *
 * <p>weekStartDate는 주차 식별 필수 값이라 {@code @NotNull}(누락 시 400 E-COM-001). 요일 정렬(사용자
 * week_start_day)은 검증하지 않는다(클라이언트가 준 날짜를 그대로 주차 시작으로 사용).
 */
public record WeeklyPlanCreateRequest(
        @NotNull(message = "weekStartDate는 필수입니다.") LocalDate weekStartDate) {
}
