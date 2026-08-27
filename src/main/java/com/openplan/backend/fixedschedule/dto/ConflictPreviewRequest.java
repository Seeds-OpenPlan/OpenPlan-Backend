package com.openplan.backend.fixedschedule.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 고정 일정 충돌 미리보기 요청 (FIX-07 / 정본 openapi.yaml {@code previewFixedScheduleConflicts}).
 * 본문은 {@code candidate} 하나 — 저장 전 후보 값이다(무영속).
 *
 * <p>{@code candidate}는 {@code FixedScheduleInput} + {@code fixedScheduleId}(편집 시 기존 ID).
 * {@code weekday}를 String으로 받는 이유는 {@link FixedScheduleCreateRequest}와 동일하다 —
 * 미정의값이 파싱 단계 500으로 새지 않고 422 E-COM-009로 판정되도록.
 */
public record ConflictPreviewRequest(Candidate candidate) {

    public record Candidate(
            String title,
            String weekday,
            LocalTime startTime,
            LocalTime endTime,
            LocalDate startDate,
            LocalDate endDate,
            java.util.UUID fixedScheduleId) {
    }
}
