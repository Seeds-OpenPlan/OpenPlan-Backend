package com.openplan.backend.rule.model;

import java.time.DayOfWeek;
import java.time.LocalTime;

/** 요일별 가용 시간 창 (요일 7행). active=false 는 가용 총량 계산에서 제외. */
public record AvailabilityWindow(DayOfWeek weekday, LocalTime startTime, LocalTime endTime,
                                 boolean active) {}
