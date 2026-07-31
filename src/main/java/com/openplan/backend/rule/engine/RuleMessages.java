package com.openplan.backend.rule.engine;

import java.time.DayOfWeek;

/**
 * 판정 사유 문구 정본 (C-3: reason 는 null 불가).
 *
 * 계약 확정(rule-engine-contract §3.3, 2026-07-25): 문구 정본은 <b>이 클래스의 코드 상수</b>다.
 *   초안이던 {@code resources/rules/messages.properties}는 폐기 — 리소스 로딩이 C-1(엔진 순수성·IO 금지,
 *   W6 ArchUnit 강제)과 충돌하고 MVP1에 다국어 요구가 없다. 문구 변경 = 코드 변경 = 골든 갱신(의도된 마찰).
 * 요일 한글 표기도 {@code Locale}/CLDR 대신 아래 상수를 쓴다 — JDK 로케일 데이터 판올림이 골든을 깨지 않도록.
 * 숫자도 {@code String.format} 대신 문자열 결합 — 기본 로케일에 따라 자릿수 표기가 달라지지 않도록(P1).
 */
final class RuleMessages {
    private RuleMessages() {}

    /** 한글 요일. Locale 의존 금지(P1 결정성). */
    static String weekdayKo(DayOfWeek weekday) {
        return switch (weekday) {
            case MONDAY -> "월요일";
            case TUESDAY -> "화요일";
            case WEDNESDAY -> "수요일";
            case THURSDAY -> "목요일";
            case FRIDAY -> "금요일";
            case SATURDAY -> "토요일";
            case SUNDAY -> "일요일";
        };
    }

    /**
     * V3 가용시간 초과. 템플릿(정본):
     * {@code "{요일} 배치 시간이 가용 시간을 초과했습니다. (가용 {capacity}분 / 배치 {placed}분, {excess}분 초과)"}
     *
     * <p>수치를 문구에 넣는 이유: {@code ValidationIssue}에 수치 필드가 없고, C-3(사유 동반)의 취지가
     * "사용자가 조치할 수 있는 사유"이기 때문. FE 표시 문구는 이 문자열을 그대로 쓴다(공용 규약: BE 기준 통일).
     */
    static String v3CapacityExceeded(DayOfWeek weekday, long capacityMinutes, long placedMinutes) {
        return weekdayKo(weekday) + " 배치 시간이 가용 시간을 초과했습니다."
                + " (가용 " + capacityMinutes + "분 / 배치 " + placedMinutes + "분, "
                + (placedMinutes - capacityMinutes) + "분 초과)";
    }
}
