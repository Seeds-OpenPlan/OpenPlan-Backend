package com.openplan.backend.weeklyplan.domain;

/**
 * 계획 블록 상태 (ST-B2-08). V1 {@code ck_plan_block_status} CHECK와 1:1 — 2값.
 * SCHEDULED: 배치됨(기본). COMPLETED: 완료(태스크 완료 토글이 미러 — ST-B2-03 PlanBlockStatusMirror).
 */
public enum PlanBlockStatus {
    SCHEDULED,
    COMPLETED
}
