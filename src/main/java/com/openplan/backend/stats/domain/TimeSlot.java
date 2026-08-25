package com.openplan.backend.stats.domain;

import java.time.LocalTime;

/**
 * 구간별 완료율(RB-STAT-03/SS-12)의 고정 4구간. 경계는 정본에 명시돼 있어 상수화한다
 * (data-model-erd.md:187 · service-stories.md SS-12) — 새벽 00:00~05:59 · 오전 06:00~11:59 ·
 * 오후 12:00~17:59 · 야간 18:00~23:59, 전부 사용자 timezone 기준 로컬 시각으로 판정한다.
 */
public enum TimeSlot {
    DAWN, MORNING, AFTERNOON, NIGHT;

    /** localTime(사용자 zone 기준으로 이미 변환된 값)이 속한 고정 구간을 결정적으로 판정한다. */
    public static TimeSlot fromLocalTime(LocalTime localTime) {
        int hour = localTime.getHour();
        if (hour < 6) {
            return DAWN;
        } else if (hour < 12) {
            return MORNING;
        } else if (hour < 18) {
            return AFTERNOON;
        } else {
            return NIGHT;
        }
    }
}
