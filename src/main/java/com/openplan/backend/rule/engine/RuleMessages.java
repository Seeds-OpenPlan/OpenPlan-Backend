package com.openplan.backend.rule.engine;

import java.time.DayOfWeek;
import java.time.LocalTime;

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

    /**
     * V1 일정 겹침. 템플릿(정본, 계약 §3.3 · ADR-0013):
     * {@code "{요일} {HH:MM}~{HH:MM} 배치가 {HH:MM}~{HH:MM} 배치와 겹칩니다. ({n}분 겹침)"}
     *
     * <p>기준(첫 요일·첫 구간) = {@code planBlockId} 블록, 요일은 zone 기준 {@code startAt} 의 요일
     * (V3 경계 e·V6 "배치일 = zone 기준 startAt 날짜" 승계). 상대 블록의 startAt 요일이 기준과 다르면
     * (자정 교차 쌍) 상대 구간 앞에 {@code "{상대요일} "} 접두를 붙인다.
     *
     * <p>블록 제목은 넣지 않는다 — {@code BlockView}(계약 §3.1)에 제목 필드가 없고, 넣으면 골든이
     * 사용자 자유 문자열에 결합된다(제목 변경 = 판정 문구 변경). FE 는 planBlockId·counterpartId 로
     * 클라이언트 조인한다(ADR-0012 결정② 선례).
     *
     * <p>C-3 근거는 V3와 동일: {@code ValidationIssue} 에 시각 필드가 없으므로 요일·두 구간·겹침 분을
     * 문구에 담아야 사용자가 한쪽을 이동/삭제하는 조치(PLAN-24)를 할 수 있다.
     */
    static String v1Overlap(DayOfWeek baseWeekday, LocalTime baseStart, LocalTime baseEnd,
                            DayOfWeek counterpartWeekday, LocalTime counterpartStart, LocalTime counterpartEnd,
                            long overlapMinutes) {
        String counterpartPrefix =
                counterpartWeekday == baseWeekday ? "" : weekdayKo(counterpartWeekday) + " ";
        return weekdayKo(baseWeekday) + " " + hhmm(baseStart) + "~" + hhmm(baseEnd)
                + " 배치가 " + counterpartPrefix + hhmm(counterpartStart) + "~" + hhmm(counterpartEnd)
                + " 배치와 겹칩니다. (" + overlapMinutes + "분 겹침)";
    }

    /**
     * V2 고정 일정 충돌. 템플릿(정본, 계약 §3.3 · ADR-0013):
     * {@code "{요일} {HH:MM}~{HH:MM} 배치가 {HH:MM}~{HH:MM} 고정 일정과 겹칩니다. ({n}분 겹침)"}
     *
     * <p>계약 §3.3 V2 행이 {@code reason} 을 <b>"W4 구현 시 V1과 같은 방식(시간 기반·문자열 결합)으로 확정"</b>
     * 으로 위임해 두었으므로, V1 템플릿을 그대로 승계하되 상대 표기만 {@code "배치"} → {@code "고정 일정"} 으로
     * 바꾼다. 상대를 구분하는 이유는 사용자의 조치가 다르기 때문 — 겹친 상대가 배치면 어느 쪽을 옮겨도 되지만,
     * 고정 일정이면 옮길 수 있는 건 배치 쪽뿐이다(고정 일정 변경은 별도 화면·별도 결정).
     *
     * <p>기준(첫 요일·첫 구간) = {@code planBlockId} 블록. V2 는 쌍의 한쪽이 항상 블록이라 대표 선정이
     * 불요하다(계약 §3.3 "V2는 항상 블록 쪽 — 선정 불요"). 고정 일정 창은 자정을 넘지 못하므로
     * ({@code FixedScheduleValidator.validateTimes} 의 {@code startTime < endTime} · DB {@code ck_fixed_range})
     * 창 자체는 항상 단일 날짜지만, <b>블록</b>이 자정을 넘으면 블록 요일과 창 요일이 달라진다.
     * 그 경우 V1과 같은 규약으로 상대 구간 앞에 {@code "{창요일} "} 접두를 붙인다.
     *
     * <p>고정 일정 제목은 넣지 않는다 — {@code FixedWindow}(계약 §3.1)에 제목 필드가 없고, 넣으면 골든이
     * 사용자 자유 문자열에 결합된다(V1 선례와 동일 근거). FE 는 {@code counterpartId}(= {@code fixedScheduleId})
     * 로 클라이언트 조인한다.
     */
    static String v2FixedConflict(DayOfWeek blockWeekday, LocalTime blockStart, LocalTime blockEnd,
                                  DayOfWeek fixedWeekday, LocalTime fixedStart, LocalTime fixedEnd,
                                  long overlapMinutes) {
        String fixedPrefix = fixedWeekday == blockWeekday ? "" : weekdayKo(fixedWeekday) + " ";
        return weekdayKo(blockWeekday) + " " + hhmm(blockStart) + "~" + hhmm(blockEnd)
                + " 배치가 " + fixedPrefix + hhmm(fixedStart) + "~" + hhmm(fixedEnd)
                + " 고정 일정과 겹칩니다. (" + overlapMinutes + "분 겹침)";
    }

    /**
     * {@code HH:MM} 수동 0패딩. {@code DateTimeFormatter} 를 포함한 모든 포매터를 쓰지 않는다 —
     * 기본 Locale 에 의존해 JDK 로케일 데이터 판올림이 골든을 깨뜨릴 수 있기 때문(P1, V3 선례 승계).
     */
    private static String hhmm(LocalTime time) {
        return pad2(time.getHour()) + ":" + pad2(time.getMinute());
    }

    private static String pad2(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
