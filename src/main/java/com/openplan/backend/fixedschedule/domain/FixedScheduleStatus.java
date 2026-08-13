package com.openplan.backend.fixedschedule.domain;

/**
 * 고정 일정 상태 (baseline {@code fixed_schedules.status}, ck_fixed_status).
 *
 * <p>ADR-0011·B12: 이 값은 <b>서버 관리 미러</b>다 — EXTERNAL 유래의 연동 활성 상태(FIX-16)를 반영한다.
 * MANUAL은 항상 ACTIVE. PLAN-33/34의 주차 한정 제외는 이 컬럼이 아니라 {@code fixed_schedule_week_exceptions}.
 */
public enum FixedScheduleStatus {
    ACTIVE, INACTIVE
}
