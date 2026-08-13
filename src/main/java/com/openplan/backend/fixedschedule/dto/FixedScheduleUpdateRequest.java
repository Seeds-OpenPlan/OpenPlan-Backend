package com.openplan.backend.fixedschedule.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 고정 일정 편집 요청 (FIX-06 / 정본 openapi.yaml {@code FixedScheduleInput} + version).
 *
 * <p>PUT-style <b>전체 교체</b> — 편집 가능 필드(title·weekday·startTime·endTime·startDate·endDate)를 모두 실어
 * 보낸다(생략된 기간 필드는 null로 교체). {@code version}은 낙관락 입력이라 필수(누락 → 400).
 * 값 규칙(제목·요일·5분 단위·기간)은 생성과 동일하게 {@code FixedScheduleValidator}가 422로 판정한다.
 * source·status는 서버 관리라 편집 대상이 아니다(요청에 없음).
 */
public record FixedScheduleUpdateRequest(
        String title,
        String weekday,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate startDate,
        LocalDate endDate,
        @NotNull(message = "version은 필수입니다.") Long version) {
}
