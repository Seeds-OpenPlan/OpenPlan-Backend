package com.openplan.backend.dashboard.dto;

/** DASH-07 손 볼 요일 1행 — us-decisions-kr.md §5.2. 전 요일 잔여율 ≥50%면 빈 배열(손 볼 요일 없음). */
public record BusyWeekdayResponse(String weekday, double remainingAvailabilityPercent) {
}
