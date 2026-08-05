package com.openplan.backend.weeklyplan.domain;

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
import java.util.UUID;

/**
 * 주간 계획 (ST-B2-07). V1 baseline {@code weekly_plans} 매핑 — 스키마 델타 0. ddl-auto=validate이므로
 * 컬럼·타입이 스키마와 정확히 일치해야 한다.
 *
 * <p><b>사용자·주차 단위</b>: {@code (user_id, week_start_date)} UNIQUE로 사용자당 주차별 1개. week_end_date는
 * 서버가 start+6일로 계산(7일 주). {@code total_planned_minutes}는 블록 합의 반정규화 캐시(블록 변경 tx에서 갱신 —
 * ST-B2-08). status는 생성 시 DRAFT, 확정 전이는 ST-B2-09.
 *
 * <p><b>시각 소스(P-2)</b>: createdAt·confirmedAt은 서비스가 {@code UserClock}로 주입 — 엔티티 {@code now()} 금지.
 */
@Getter
@Entity
@Table(name = "weekly_plans")
public class WeeklyPlan {

    @Id
    @Column(name = "weekly_plan_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "week_start_date", nullable = false, updatable = false)
    private LocalDate weekStartDate;

    @Column(name = "week_end_date", nullable = false, updatable = false)
    private LocalDate weekEndDate;

    @Column(name = "total_planned_minutes", nullable = false)
    private int totalPlannedMinutes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WeeklyPlanStatus status;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected WeeklyPlan() {
    }

    /**
     * 생성 규정 (ST-B2-07): status=DRAFT, total=0, confirmedAt=null, version=0. id·createdAt는 앱 측 확정
     * (createdAt=UserClock 주입값, P-2). weekEndDate는 서비스가 start+6일로 계산해 넘긴 값.
     */
    public WeeklyPlan(UUID userId, LocalDate weekStartDate, LocalDate weekEndDate, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.weekStartDate = weekStartDate;
        this.weekEndDate = weekEndDate;
        this.totalPlannedMinutes = 0;
        this.status = WeeklyPlanStatus.DRAFT;
        this.confirmedAt = null;
        this.createdAt = createdAt;
    }

}
