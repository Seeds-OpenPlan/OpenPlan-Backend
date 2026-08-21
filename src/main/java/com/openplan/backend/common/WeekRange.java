package com.openplan.backend.common;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 사용자 정의 주 시작 요일(user_profiles.week_start_day) 기준 "이번 주" 범위 산출.
 *
 * <p>weeklyplan 도메인은 weekStartDate를 화면이 이미 계산해 넘겨받는 방식이라(WeeklyPlanQuery) 이 파생
 * 계산이 없었다. dashboard·stats 양쪽(baseDate만 받는 라우트)이 똑같이 필요해 common에 둔다 — 값 창작이
 * 아니라 요일 오프셋 산술이라 위험도가 낮다(us-decisions-kr.md에 별도 계산식이 없어 W3 확정 대상이 아님).
 *
 * <p>{@link Weekday}의 선언 순서(MON..SUN)가 {@link DayOfWeek#getValue()}(월=1..일=7)와 1:1 대응한다는
 * 전제로 오프셋을 계산한다 — 두 enum의 선언 순서를 바꾸면 이 계산이 깨진다.
 */
public record WeekRange(LocalDate start, LocalDate end) {

    /** baseDate가 속한 주의 [시작일, 종료일](양끝 포함, 7일)을 weekStartDay 기준으로 계산한다. */
    public static WeekRange of(LocalDate baseDate, Weekday weekStartDay) {
        int baseOrdinal = baseDate.getDayOfWeek().getValue() - 1; // MON=0..SUN=6
        int startOrdinal = weekStartDay.ordinal();
        int offset = Math.floorMod(baseOrdinal - startOrdinal, 7);
        LocalDate start = baseDate.minusDays(offset);
        return new WeekRange(start, start.plusDays(6));
    }
}
