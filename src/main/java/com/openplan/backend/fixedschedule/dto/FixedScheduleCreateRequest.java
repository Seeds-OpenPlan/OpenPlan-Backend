package com.openplan.backend.fixedschedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 고정 일정 생성 요청 (FIX-05 / 정본 openapi.yaml {@code FixedScheduleInput}).
 *
 * <p>{@code weekday}는 <b>String으로 받는다</b> — enum 바인딩 시 미정의값이 파싱 단계에서 500으로 새는 것을 피하고,
 * 값 규칙 위반을 서비스가 422 E-COM-009로 판정하기 위함(블록 {@code blockType}과 동일 컨벤션).
 * null/공백/길이 규칙은 Bean Validation을 두지 않고 {@code FixedScheduleValidator}가 422로 판정한다.
 * {@code startTime}/{@code endTime}은 "HH:mm[:ss]"(JavaTimeModule), {@code startDate}/{@code endDate}는 선택(기간 한정).
 */
public record FixedScheduleCreateRequest(
        String title,
        String weekday,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate startDate,
        LocalDate endDate) {
}
