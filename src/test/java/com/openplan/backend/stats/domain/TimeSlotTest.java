package com.openplan.backend.stats.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link TimeSlot} 4구간 경계 단위 테스트 — data-model-erd.md:187 고정 경계. */
class TimeSlotTest {

    @Test
    void DAWN_00시_경계() {
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(0, 0))).isEqualTo(TimeSlot.DAWN);
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(5, 59))).isEqualTo(TimeSlot.DAWN);
    }

    @Test
    void MORNING_06시_경계() {
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(6, 0))).isEqualTo(TimeSlot.MORNING);
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(11, 59))).isEqualTo(TimeSlot.MORNING);
    }

    @Test
    void AFTERNOON_12시_경계() {
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(12, 0))).isEqualTo(TimeSlot.AFTERNOON);
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(17, 59))).isEqualTo(TimeSlot.AFTERNOON);
    }

    @Test
    void NIGHT_18시_경계() {
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(18, 0))).isEqualTo(TimeSlot.NIGHT);
        assertThat(TimeSlot.fromLocalTime(LocalTime.of(23, 59))).isEqualTo(TimeSlot.NIGHT);
    }
}
