package com.openplan.backend.user.domain;

/**
 * 요일 enum — 주 시작 요일(user_profiles.week_start_day) 및 가용 시간 요일(availability_patterns)에 사용.
 *
 * <p>값 이름은 DB CHECK 제약(ck_user_profiles_week_start: MON..SUN)·openapi {@code Weekday} 스키마와
 * 1:1로 고정한다 — 이름이 곧 저장 문자열이므로 임의 변경 금지(VARCHAR(3) 한도도 이 이름들이 전제).
 */
public enum Weekday {
    MON, TUE, WED, THU, FRI, SAT, SUN
}
