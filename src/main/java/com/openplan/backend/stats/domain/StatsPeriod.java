package com.openplan.backend.stats.domain;

/**
 * 통계 집계 단위 (SS-10/12, openapi {@code period} enum과 1:1). WEEKLY는 사용자 주 시작 요일
 * (user_profiles.week_start_day) 기준 7일, MONTHLY는 달력 월(1일~말일) — 명세에 별도 계산식이
 * 없어 달력 월을 그대로 쓴다(값 창작이 아니라 "월"의 통상적 의미).
 */
public enum StatsPeriod {
    WEEKLY, MONTHLY
}
