package com.openplan.backend.weeklyplan.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 계획 블록 (ST-B2-08). V1 baseline {@code plan_blocks} 매핑 — 스키마 델타 0.
 *
 * <p><b>주간계획 소속</b>: weekly_plan_id NOT NULL(반드시 어떤 주간계획에 속함). block_type=TASK면 task_id만,
 * SCHEDULE이면 schedule_id만(ck_plan_block_ref 배타). start_at &lt; end_at(ck_plan_block_range). version 없음.
 *
 * <p><b>시각 소스(P-2)</b>: createdAt은 서비스가 {@code UserClock}로 주입. start/end는 요청값(사용자가 배치한 시각).
 */
@Getter
@Entity
@Table(name = "plan_blocks")
public class PlanBlock {

    @Id
    @Column(name = "plan_block_id")
    private UUID id;

    @Column(name = "weekly_plan_id", nullable = false, updatable = false)
    private UUID weeklyPlanId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "block_type", nullable = false, length = 10)
    private PlanBlockType blockType;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlanBlockStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected PlanBlock() {
    }

    /**
     * TASK 블록 생성 (PLAN-06/07). status=SCHEDULED. task_id만 세팅(schedule_id=null — ck_plan_block_ref).
     * start/end는 서비스가 검증(start&lt;end)한 값, createdAt은 UserClock 주입값(P-2).
     */
    public static PlanBlock forTask(UUID weeklyPlanId, UUID taskId, Instant startAt, Instant endAt, Instant createdAt) {
        PlanBlock b = new PlanBlock();
        b.id = UUID.randomUUID();
        b.weeklyPlanId = weeklyPlanId;
        b.taskId = taskId;
        b.scheduleId = null;
        b.blockType = PlanBlockType.TASK;
        b.startAt = startAt;
        b.endAt = endAt;
        b.status = PlanBlockStatus.SCHEDULED;
        b.createdAt = createdAt;
        return b;
    }
}
