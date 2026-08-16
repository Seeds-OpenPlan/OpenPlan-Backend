package com.openplan.backend.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link WeekRange} 순수 함수 단위 테스트 — DB·Spring 불요. */
class WeekRangeTest {

    @Test
    void 주시작요일이_MON이면_baseDate가_속한_월요일부터_일요일까지() {
        // 2026-07-15는 수요일 — MON 시작 주는 07-13(월)~07-19(일)
        WeekRange range = WeekRange.of(LocalDate.of(2026, 7, 15), Weekday.MON);

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 7, 19));
    }

    @Test
    void baseDate_자신이_주시작요일이면_그날부터_시작() {
        // 2026-07-13은 월요일 — MON 시작 주의 첫날
        WeekRange range = WeekRange.of(LocalDate.of(2026, 7, 13), Weekday.MON);

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 7, 19));
    }

    @Test
    void 주시작요일이_SUN이면_baseDate가_속한_일요일부터_토요일까지() {
        // 2026-07-15(수) 기준 SUN 시작 주 = 07-12(일)~07-18(토)
        WeekRange range = WeekRange.of(LocalDate.of(2026, 7, 15), Weekday.SUN);

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 7, 12));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 7, 18));
    }

    @Test
    void 항상_7일_구간이다() {
        for (Weekday weekday : Weekday.values()) {
            WeekRange range = WeekRange.of(LocalDate.of(2026, 7, 15), weekday);
            assertThat(range.end()).isEqualTo(range.start().plusDays(6));
        }
    }
}
