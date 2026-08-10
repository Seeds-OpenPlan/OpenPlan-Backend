package com.openplan.backend.schedule.domain;

/**
 * 일정 상태 (schedules.status). 생성 시 ACTIVE. INACTIVE는 후속(PLAN-33 비활성화 등).
 * 스키마엔 CHECK가 없어(자유 VARCHAR) enum으로 값을 통제한다.
 */
public enum ScheduleStatus {
    ACTIVE,
    INACTIVE
}
