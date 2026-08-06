package com.openplan.backend.weeklyplan.domain;

/**
 * 계획 블록 종류 (ST-B2-08). V1 {@code ck_plan_block_type} CHECK와 1:1.
 * TASK: 프로젝트 태스크 배치(task_id). SCHEDULE: 프로젝트 무관 일정 배치(schedule_id).
 */
public enum PlanBlockType {
    TASK,
    SCHEDULE
}
