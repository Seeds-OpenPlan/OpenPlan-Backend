package com.openplan.backend.executionlog.domain;

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
 * 수행 이력 (PLAN-15 · DASH-05) — V1 baseline {@code execution_logs} 매핑.
 *
 * <p>W5 수행 통계(요약·편차·시간 패턴·보정 제안)가 먹는 연료다. 통계는 별도 집계 테이블 없이
 * 이 행들을 온더플라이로 집계한다(B9 — {@code execution_stats} 미생성).
 *
 * <p>DB 제약과 이중 방어한다: {@code ck_exec_result}(3값) · {@code ck_exec_range}(started&le;ended) ·
 * {@code ck_exec_actual}(5분 배수·양수). 서버 검증은 {@code ExecutionLogValidator}가 저장 전에 먼저 건다.
 *
 * <p>{@code user_id}를 직접 보유한다 — 통계 조회가 {@code ix_exec_logs_user_started}(user_id, started_at)로
 * 바로 타야 하기 때문이다(태스크·프로젝트 조인 없이). 태스크 소유 판정은 기록 시점에 이미 끝나 있다.
 * version 없음 — 기록은 추가만 하고 편집하지 않는다.
 */
@Getter
@Entity
@Table(name = "execution_logs")
public class ExecutionLog {

    @Id
    @Column(name = "execution_log_id")
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    /** 어느 계획 블록을 수행한 것인지(선택). 블록 삭제 시 FK ON DELETE SET NULL — 이력 자체는 남는다. */
    @Column(name = "plan_block_id")
    private UUID planBlockId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "actual_minutes")
    private Integer actualMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false)
    private ExecutionResult result;

    @Column(name = "memo")
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected ExecutionLog() {
    }

    /** 신규 기록 — id는 앱에서 부여, createdAt은 {@code UserClock}(P-2). */
    public static ExecutionLog record(UUID userId, UUID taskId, UUID planBlockId,
                                      Instant startedAt, Instant endedAt, Integer actualMinutes,
                                      ExecutionResult result, String memo, Instant createdAt) {
        ExecutionLog log = new ExecutionLog();
        log.id = UUID.randomUUID();
        log.userId = userId;
        log.taskId = taskId;
        log.planBlockId = planBlockId;
        log.startedAt = startedAt;
        log.endedAt = endedAt;
        log.actualMinutes = actualMinutes;
        log.result = result;
        log.memo = memo;
        log.createdAt = createdAt;
        return log;
    }
}
