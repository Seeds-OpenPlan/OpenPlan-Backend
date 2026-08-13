package com.openplan.backend.fixedschedule.domain;

/**
 * 고정 일정 출처 (baseline {@code fixed_schedules.source}, ck_fixed_source).
 *
 * <p><b>MANUAL</b>: 사용자가 직접 등록(FIX-05) — connection_id 없음.
 * <b>EXTERNAL</b>: 외부 캘린더 연동 유래(ONB-09) — connection_id 필수(ck_fixed_origin). 이번 슬라이스 범위 밖.
 */
public enum FixedScheduleSource {
    MANUAL, EXTERNAL
}
