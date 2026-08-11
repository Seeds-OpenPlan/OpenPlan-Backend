package com.openplan.backend.fixedschedule.dto;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.fixedschedule.domain.FixedSchedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 고정 일정 응답 (생성·목록 항목 공용) — 정본 openapi.yaml {@code FixedSchedule} shape.
 * {@code source}(MANUAL/EXTERNAL)·{@code status}(ACTIVE/INACTIVE)는 서버 관리 값. createdAt은 스키마에 없어 미포함.
 */
public record FixedScheduleResponse(
        UUID fixedScheduleId,
        String title,
        Weekday weekday,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate startDate,
        LocalDate endDate,
        String source,
        String status,
        long version) {

    public static FixedScheduleResponse from(FixedSchedule fs) {
        return new FixedScheduleResponse(
                fs.getId(),
                fs.getTitle(),
                fs.getWeekday(),
                fs.getStartTime(),
                fs.getEndTime(),
                fs.getStartDate(),
                fs.getEndDate(),
                fs.getSource().name(),
                fs.getStatus().name(),
                fs.getVersion());
    }
}
