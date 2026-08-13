package com.openplan.backend.fixedschedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 고정 일정 주차 한정 예외 (PLAN-33/34) — V1 baseline {@code fixed_schedule_week_exceptions} 매핑.
 *
 * <p>행 존재 = <b>그 주에서만</b> 이 고정 일정을 배치 제약·V2 판정에서 제외(B12). 다른 주는 무영향.
 * UNIQUE(fixed_schedule_id, week_start_date)로 주차별 1행. created_at은 미매핑 — DB DEFAULT now()가 채운다
 * (응답에 안 쓰임). 고정 일정 삭제 시 FK ON DELETE CASCADE로 함께 삭제된다.
 */
@Getter
@Entity
@Table(name = "fixed_schedule_week_exceptions")
public class FixedScheduleWeekException {

    @Id
    @Column(name = "exception_id")
    private UUID id;

    @Column(name = "fixed_schedule_id", nullable = false, updatable = false)
    private UUID fixedScheduleId;

    @Column(name = "week_start_date", nullable = false, updatable = false)
    private LocalDate weekStartDate;

    /** JPA 전용. */
    protected FixedScheduleWeekException() {
    }

    /** 신규 예외 — id는 앱에서 부여, created_at은 DB DEFAULT now(). */
    public static FixedScheduleWeekException create(UUID fixedScheduleId, LocalDate weekStartDate) {
        FixedScheduleWeekException e = new FixedScheduleWeekException();
        e.id = UUID.randomUUID();
        e.fixedScheduleId = fixedScheduleId;
        e.weekStartDate = weekStartDate;
        return e;
    }
}
