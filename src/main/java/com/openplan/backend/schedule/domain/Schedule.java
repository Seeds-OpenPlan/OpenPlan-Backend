package com.openplan.backend.schedule.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 일정 (PLAN-08/17). V1 baseline {@code schedules} 매핑 — 프로젝트와 무관한 개인 일정(태스크와 별개).
 *
 * <p>이번 스토리(ST-B2-08)에서는 SCHEDULE 블록 배치와 <b>함께 생성</b>된다(PLAN-08: 제목·예상시간·우선순위·메모 입력).
 * 단독 편집/삭제(PLAN-17/18)는 후속. start/end는 배치 시각(블록과 동일). version 낙관락, createdAt은 UserClock 주입.
 */
@Getter
@Entity
@Table(name = "schedules")
public class Schedule {

    @Id
    @Column(name = "schedule_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "estimated_minutes")
    private Integer estimatedMinutes;

    private Integer priority;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(columnDefinition = "text")
    private String memo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected Schedule() {
    }

    /**
     * 생성 규정 (PLAN-08): status=ACTIVE, version=0. title은 서비스가 trim·검증한 값,
     * estimatedMinutes·priority는 검증 통과값(null 허용), createdAt은 UserClock 주입값(P-2).
     */
    public Schedule(UUID userId, String title, Integer estimatedMinutes, Integer priority,
                    Instant startAt, Instant endAt, String memo, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.title = title;
        this.estimatedMinutes = estimatedMinutes;
        this.priority = priority;
        this.startAt = startAt;
        this.endAt = endAt;
        this.memo = memo;
        this.status = ScheduleStatus.ACTIVE;
        this.createdAt = createdAt;
    }
}
