package com.openplan.backend.rule.model;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 해당 주 유효 고정 일정 창. 주차 예외(PLAN-33)·INACTIVE 제외는 스냅샷 조립(BE-2) 소관 —
 * 엔진은 받은 창을 전부 유효로 취급 (B12). V2에서 사용(현재 미구현).
 */
public record FixedWindow(UUID fixedScheduleId, DayOfWeek weekday,
                          LocalTime startTime, LocalTime endTime,
                          LocalDate effectiveFrom, LocalDate effectiveTo) {}
