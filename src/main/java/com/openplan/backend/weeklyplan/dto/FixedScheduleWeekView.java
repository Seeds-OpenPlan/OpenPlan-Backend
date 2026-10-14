package com.openplan.backend.weeklyplan.dto;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.fixedschedule.domain.FixedSchedule;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 고정 일정 + 주차 상태 항목 (이슈 #90) — 정본 openapi.yaml {@code WeeklyPlanView.fixedSchedules[]}
 * (= {@code FixedSchedule} allOf {@code activeThisWeek}). {@link WeeklyPlanView}가 this를
 * 최상위 배열로 노출한다(FE는 {@code data.fixedSchedules}로 읽는다).
 *
 * <p>필드 shape은 {@link com.openplan.backend.fixedschedule.dto.FixedScheduleResponse}와 같다 —
 * 레코드를 감싸지 않고 평탄화해 중복 선언한 이유는 JSON allOf를 평평한 한 겹으로 직렬화해야 해서다
 * (중첩하면 FE가 {@code data.fixedSchedules[0].fixedSchedule.title} 처럼 한 겹 더 파야 한다).
 */
public record FixedScheduleWeekView(
        UUID fixedScheduleId,
        String title,
        Weekday weekday,
        LocalTime startTime,
        LocalTime endTime,
        LocalDate startDate,
        LocalDate endDate,
        String source,
        String status,
        long version,
        boolean activeThisWeek) {

    /**
     * 고정 일정 엔티티 + 그 주 활성 여부 → 뷰. {@code activeThisWeek}는 호출자가 그 주 예외·status를
     * 판정해 넘긴다({@link com.openplan.backend.weeklyplan.service.FixedScheduleWeekAssembler} 소관) —
     * 이 레코드 자체는 판정 로직을 모른다(순수 매핑).
     */
    public static FixedScheduleWeekView of(FixedSchedule fs, boolean activeThisWeek) {
        return new FixedScheduleWeekView(
                fs.getId(),
                fs.getTitle(),
                fs.getWeekday(),
                fs.getStartTime(),
                fs.getEndTime(),
                fs.getStartDate(),
                fs.getEndDate(),
                fs.getSource().name(),
                fs.getStatus().name(),
                fs.getVersion(),
                activeThisWeek);
    }
}
