package com.openplan.backend.fixedschedule.domain;

import com.openplan.backend.common.Weekday;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 고정 일정 (FIX-04~09) — V1 baseline {@code fixed_schedules} 매핑. 요일·시간으로 반복되는 배치 불가 시간대.
 *
 * <p>이번 슬라이스(FIX-05 생성)는 <b>MANUAL만</b> 만든다 — source=MANUAL, connection_id=null, status=ACTIVE
 * (ck_fixed_origin·ck_fixed_status). EXTERNAL 유래(연동)와 편집·삭제·주차예외는 후속.
 *
 * <p>DB 제약과 이중 방어: start_time&lt;end_time(ck_fixed_range)·5분 단위(ck_fixed_step)·요일 enum(ck_fixed_weekday)·
 * start_date&le;end_date(ck_fixed_dates)를 서버가 저장 전 검증한다(422 E-COM-009). version 낙관락, createdAt은 UserClock(P-2).
 */
@Getter
@Entity
@Table(name = "fixed_schedules")
public class FixedSchedule {

    @Id
    @Column(name = "fixed_schedule_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** EXTERNAL 유래 연결(FIX-17 연쇄 삭제용). MANUAL은 null(ck_fixed_origin). */
    @Column(name = "connection_id")
    private UUID connectionId;

    @Column(nullable = false, length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Weekday weekday;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** 반복 규칙(RRULE 등) — 현재 미사용, MANUAL 생성 시 null. */
    @Column(name = "recurrence_rule", length = 255)
    private String recurrenceRule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FixedScheduleSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FixedScheduleStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected FixedSchedule() {
    }

    /**
     * MANUAL 고정 일정 생성 (FIX-05): source=MANUAL, status=ACTIVE, connectionId=null, version=0.
     * 값들은 {@code FixedScheduleValidator} 통과값(title은 trim, weekday는 매핑된 enum, 시각/날짜는 검증 완료).
     */
    public static FixedSchedule createManual(UUID userId, String title, Weekday weekday,
                                             LocalTime startTime, LocalTime endTime,
                                             LocalDate startDate, LocalDate endDate, Instant createdAt) {
        FixedSchedule fs = new FixedSchedule();
        fs.id = UUID.randomUUID();
        fs.userId = userId;
        fs.connectionId = null;
        fs.title = title;
        fs.weekday = weekday;
        fs.startTime = startTime;
        fs.endTime = endTime;
        fs.startDate = startDate;
        fs.endDate = endDate;
        fs.recurrenceRule = null;
        fs.source = FixedScheduleSource.MANUAL;
        fs.status = FixedScheduleStatus.ACTIVE;
        fs.createdAt = createdAt;
        return fs;
    }

    /**
     * 편집 (FIX-06) — 전체 교체. 검증 통과값으로 편집 가능 필드를 갈아끼운다. source·status·connectionId는
     * 편집으로 못 바꾼다(서버 관리·출처 불변). version은 flush 시 @Version이 증가시킨다(낙관락).
     */
    public void edit(String title, Weekday weekday, LocalTime startTime, LocalTime endTime,
                     LocalDate startDate, LocalDate endDate) {
        this.title = title;
        this.weekday = weekday;
        this.startTime = startTime;
        this.endTime = endTime;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
