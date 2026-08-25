package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
import com.openplan.backend.externalcalendar.dto.ApplyEventRequest;
import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.domain.FixedScheduleSource;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 제공자 일정 → 고정 일정 변환 (ONB-09 · ST-B1-11 AC2). 순수 단위 — DB·Docker 불요.
 *
 * <p>검증의 축은 셋이다: ⑴ 5분 배수 제약(ck_fixed_step)을 <b>넓히는 쪽</b>으로 맞추는가
 * ⑵ 1회 일정을 요일 반복 테이블에 담을 때 <b>하루로 가두는가</b> ⑶ 옮길 수 없는 일정을
 * 조용히 왜곡하지 않고 되돌리는가.
 */
class ExternalEventToFixedScheduleTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final UserClock clock = new UserClock() {
        @Override
        public Instant now() {
            return Instant.parse("2026-08-19T00:00:00Z");
        }

        @Override
        public LocalDate todayOf(UUID userId) {
            return LocalDate.of(2026, 8, 19);
        }

        @Override
        public ZoneId zoneOf(UUID userId) {
            return SEOUL;
        }

        @Override
        public Weekday weekStartDayOf(UUID userId) {
            return Weekday.MON;
        }
    };

    private final ExternalEventToFixedSchedule converter = new ExternalEventToFixedSchedule(clock);

    @Test
    @DisplayName("정각 일정은 그대로 옮겨진다 — source=EXTERNAL · 연결 id 유지")
    void convertsAlignedEvent() {
        // 2026-08-20(목) 10:00~11:00 KST
        ExternalCalendarEvent event = event("2026-08-20T01:00:00Z", "2026-08-20T02:00:00Z", "회의");

        FixedSchedule fs = converter.convert(USER, event, null);

        assertThat(fs.getSource()).isEqualTo(FixedScheduleSource.EXTERNAL);
        assertThat(fs.getConnectionId()).isEqualTo(CONNECTION);
        assertThat(fs.getTitle()).isEqualTo("회의");
        assertThat(fs.getWeekday()).isEqualTo(Weekday.THU);
        assertThat(fs.getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(fs.getEndTime()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    @DisplayName("5분 배수가 아니면 시작은 내리고 종료는 올린다 — 좁히면 약속 위에 계획이 얹힌다")
    void widensToFiveMinuteStep() {
        // 10:07 ~ 11:03 KST
        ExternalCalendarEvent event = event("2026-08-20T01:07:00Z", "2026-08-20T02:03:00Z", "면담");

        FixedSchedule fs = converter.convert(USER, event, null);

        assertThat(fs.getStartTime()).isEqualTo(LocalTime.of(10, 5));
        assertThat(fs.getEndTime()).isEqualTo(LocalTime.of(11, 5));
    }

    @Test
    @DisplayName("1회 일정은 startDate=endDate 로 하루에 가둔다 — 안 그러면 매주 그 요일이 영구히 막힌다")
    void confinesRecurrenceToSingleDay() {
        ExternalCalendarEvent event = event("2026-08-20T01:00:00Z", "2026-08-20T02:00:00Z", "회의");

        FixedSchedule fs = converter.convert(USER, event, null);

        assertThat(fs.getStartDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(fs.getEndDate()).isEqualTo(LocalDate.of(2026, 8, 20));
    }

    @Test
    @DisplayName("자정을 넘는 일정은 422 — 임의로 자르면 남은 시간이 비어 보여 그 위에 계획이 얹힌다")
    void rejectsOvernightWithoutEdit() {
        // 2026-08-20 22:00 ~ 다음날 02:00 KST
        ExternalCalendarEvent event = event("2026-08-20T13:00:00Z", "2026-08-20T17:00:00Z", "야간작업");

        assertThatThrownBy(() -> converter.convert(USER, event, null))
                .isInstanceOf(OpenPlanException.class)
                .hasMessageContaining("자정");
    }

    @Test
    @DisplayName("자정을 넘어도 EDITED 로 시각을 정하면 통과한다 — 사용자가 판단할 길을 남긴다")
    void allowsOvernightWhenEdited() {
        ExternalCalendarEvent event = event("2026-08-20T13:00:00Z", "2026-08-20T17:00:00Z", "야간작업");
        ApplyEventRequest.Edited edited =
                new ApplyEventRequest.Edited(null, null, LocalTime.of(22, 0), LocalTime.of(23, 55));

        FixedSchedule fs = converter.convert(USER, event, edited);

        assertThat(fs.getStartTime()).isEqualTo(LocalTime.of(22, 0));
        assertThat(fs.getEndTime()).isEqualTo(LocalTime.of(23, 55));
    }

    @Test
    @DisplayName("EDITED 는 준 필드만 덮어쓴다 — 나머지는 원본 일정 값")
    void editedOverridesOnlyGivenFields() {
        ExternalCalendarEvent event = event("2026-08-20T01:00:00Z", "2026-08-20T02:00:00Z", "원래 제목");
        ApplyEventRequest.Edited edited = new ApplyEventRequest.Edited("바꾼 제목", null, null, null);

        FixedSchedule fs = converter.convert(USER, event, edited);

        assertThat(fs.getTitle()).isEqualTo("바꾼 제목");
        assertThat(fs.getWeekday()).isEqualTo(Weekday.THU);
        assertThat(fs.getStartTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("종료가 자정으로 올림되면 하루의 마지막 경계로 붙인다 — 24:00 은 존재하지 않는다")
    void clampsCeilingAtEndOfDay() {
        // 23:00 ~ 23:58 KST → 종료 올림이 24:00 이 된다
        ExternalCalendarEvent event = event("2026-08-20T14:00:00Z", "2026-08-20T14:58:00Z", "마감정리");

        FixedSchedule fs = converter.convert(USER, event, null);

        assertThat(fs.getStartTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(fs.getEndTime()).isEqualTo(LocalTime.of(23, 55));
    }

    private static ExternalCalendarEvent event(String startUtc, String endUtc, String title) {
        return ExternalCalendarEvent.candidate(CONNECTION, "ext-" + title, title,
                Instant.parse(startUtc), Instant.parse(endUtc), "내 캘린더",
                Instant.parse("2026-08-19T00:00:00Z"));
    }
}
