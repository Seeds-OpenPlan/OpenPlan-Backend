package com.openplan.backend.externalcalendar.provider.ical;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 애플 CalDAV 응답 파싱 (ST-B1-11).
 *
 * <p><b>픽스처는 2026-08-20 실계정 응답의 구조를 그대로 옮긴 것</b>이다 — 값(제목·장소·설명)만 바꿨다.
 * 구조를 지어내면 방언을 못 잡는데, 이 어댑터의 실패 모드는 전부 <b>예외 없이 조용히 틀리는</b> 것이라
 * 실물과 같은 모양이어야 의미가 있다(D-56).
 */
class ICalParsingTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 애플이 실제로 주는 모양 — 이벤트마다 {@code VTIMEZONE} 이 붙고, 그 안에 한국의 1987~88
     * 서머타임 규칙이 {@code DTSTART} 와 {@code RRULE} 로 들어 있다. 실측 143건에서 잡힌 RRULE 17건이
     * 전부 이것이었다.
     */
    private static final String APPLE_SINGLE_EVENT = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Apple//Calendar//KO
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Seoul
            BEGIN:DAYLIGHT
            TZOFFSETFROM:+0900
            TZOFFSETTO:+1000
            TZNAME:GMT+10:00
            DTSTART:19870510T000000
            RRULE:FREQ=YEARLY;UNTIL=19880507T170000Z;BYMONTH=5;BYDAY=2SU
            END:DAYLIGHT
            BEGIN:STANDARD
            TZOFFSETFROM:+1000
            TZOFFSETTO:+0900
            TZNAME:KST
            DTSTART:19871011T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:AAAA1111-2222-3333-4444-555566667777_test@icloud.com_caldavApp
            SEQUENCE:0
            SUMMARY:팀 점검
            CLASS:PUBLIC
            DTSTAMP:20260814T051847Z
            DTSTART;TZID=Asia/Seoul:20260820T100000
            DTEND;TZID=Asia/Seoul:20260820T110000
            PRIORITY:0
            LOCATION:회의실
            DESCRIPTION:설명
            TRANSP:OPAQUE
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """;

    @Nested
    @DisplayName("VTIMEZONE 격리")
    class TimezoneIsolation {

        @Test
        @DisplayName("VTIMEZONE 의 DTSTART·RRULE 을 일정 것으로 읽지 않는다")
        void 서머타임_규칙을_일정_반복으로_읽지_않는다() {
            List<ICalParser.Component> events = ICalParser.parseEvents(APPLE_SINGLE_EVENT);

            assertThat(events).hasSize(1);
            ICalParser.Component event = events.getFirst();
            // 1988년 서머타임 RRULE 이 새어 들어오면 이 단발 일정이 매년 반복으로 둔갑한다.
            assertThat(event.first("RRULE")).isNull();
            assertThat(event.value("DTSTART")).isEqualTo("20260820T100000");
        }

        @Test
        @DisplayName("VALARM 의 TRIGGER 를 일정 속성으로 담지 않는다")
        void 알람_속성이_일정에_섞이지_않는다() {
            ICalParser.Component event = ICalParser.parseEvents(APPLE_SINGLE_EVENT).getFirst();

            assertThat(event.first("TRIGGER")).isNull();
            assertThat(event.first("ACTION")).isNull();
        }
    }

    @Nested
    @DisplayName("시각 해석")
    class DateTimes {

        @Test
        @DisplayName("TZID=Asia/Seoul 은 지역 시각이다 — UTC 로 읽으면 9시간 어긋난다")
        void 지역시각을_올바로_변환한다() {
            ICalParser.Component event = ICalParser.parseEvents(APPLE_SINGLE_EVENT).getFirst();

            Instant start = ICalDateTime.parse(event.first("DTSTART"), SEOUL);

            // 2026-08-20 10:00 KST = 01:00 UTC
            assertThat(start).isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
        }

        @Test
        @DisplayName("Z 접미(UTC)도 받는다")
        void UTC_표기를_받는다() {
            String ics = wrap("""
                    DTSTART:20260820T010000Z
                    DTEND:20260820T020000Z
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            assertThat(ICalDateTime.parse(event.first("DTSTART"), SEOUL))
                    .isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
        }

        @Test
        @DisplayName("종일 일정(VALUE=DATE)은 시각이 없어 후보가 되지 않는다")
        void 종일은_거른다() {
            String ics = wrap("DTSTART;VALUE=DATE:20260820\n");
            ICalParser.Property dtStart = ICalParser.parseEvents(ics).getFirst().first("DTSTART");

            assertThat(ICalDateTime.isAllDay(dtStart)).isTrue();
            assertThat(ICalDateTime.parse(dtStart, SEOUL)).isNull();
        }

        @Test
        @DisplayName("알 수 없는 TZID 는 조회 전체를 죽이지 않는다")
        void 미지의_지역은_기본값으로_떨어진다() {
            String ics = wrap("DTSTART;TZID=Mars/Olympus:20260820T100000\n");
            ICalParser.Property dtStart = ICalParser.parseEvents(ics).getFirst().first("DTSTART");

            assertThat(ICalDateTime.parse(dtStart, SEOUL)).isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
        }
    }

    @Nested
    @DisplayName("줄 접힘·이스케이프")
    class Folding {

        @Test
        @DisplayName("접힌 줄을 펼쳐 제목이 잘리지 않는다")
        void 접힌_줄을_펼친다() {
            String ics = wrap("""
                    DTSTART;TZID=Asia/Seoul:20260820T100000
                    SUMMARY:아주 긴 제목인데 규격상 여기서 한 번 접
                     혔습니다
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            assertThat(event.value("SUMMARY")).isEqualTo("아주 긴 제목인데 규격상 여기서 한 번 접혔습니다");
        }

        @Test
        @DisplayName("이스케이프된 쉼표·세미콜론을 되돌린다")
        void 이스케이프를_해제한다() {
            String ics = wrap("SUMMARY:회의\\, 그리고 점검\\; 준비\n");

            assertThat(ICalParser.parseEvents(ics).getFirst().value("SUMMARY"))
                    .isEqualTo("회의, 그리고 점검; 준비");
        }
    }

    @Nested
    @DisplayName("반복 전개 — 애플이 안 펼쳐 주므로 우리가 한다")
    class Recurrence {

        /** 실측 probe 와 같은 모양: 6월에 시작한 매주 화요일 일정. */
        private static final String WEEKLY = wrap("""
                UID:weekly-probe@test
                DTSTART:20260602T010000Z
                DTEND:20260602T020000Z
                RRULE:FREQ=WEEKLY;BYDAY=TU
                SUMMARY:주간 회의
                """);

        @Test
        @DisplayName("조회 창 밖에서 시작한 매주 일정이 창 안의 회차로 펼쳐진다")
        void 창_밖에서_시작한_반복을_펼친다() {
            ICalParser.Component event = ICalParser.parseEvents(WEEKLY).getFirst();
            Instant from = Instant.parse("2026-08-17T00:00:00Z");
            Instant to = Instant.parse("2026-08-31T00:00:00Z");

            List<RecurrenceExpander.Occurrence> occurrences = RecurrenceExpander.expand(
                    event, Instant.parse("2026-06-02T01:00:00Z"), Instant.parse("2026-06-02T02:00:00Z"),
                    SEOUL, from, to);

            // 8/18 · 8/25 두 화요일. 마스터(6/2)를 그대로 쓰면 1건이 엉뚱한 날짜로 잡힌다.
            assertThat(occurrences).hasSize(2);
            assertThat(occurrences.getFirst().startAt()).isEqualTo(Instant.parse("2026-08-18T01:00:00Z"));
            assertThat(occurrences.getLast().startAt()).isEqualTo(Instant.parse("2026-08-25T01:00:00Z"));
        }

        @Test
        @DisplayName("EXDATE 로 지운 회차는 되살리지 않는다")
        void 제외일은_빠진다() {
            String ics = wrap("""
                    UID:weekly-ex@test
                    DTSTART:20260602T010000Z
                    DTEND:20260602T020000Z
                    RRULE:FREQ=WEEKLY;BYDAY=TU
                    EXDATE:20260818T010000Z
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            List<RecurrenceExpander.Occurrence> occurrences = RecurrenceExpander.expand(
                    event, Instant.parse("2026-06-02T01:00:00Z"), Instant.parse("2026-06-02T02:00:00Z"),
                    SEOUL, Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

            assertThat(occurrences).hasSize(1);
            assertThat(occurrences.getFirst().startAt()).isEqualTo(Instant.parse("2026-08-25T01:00:00Z"));
        }

        @Test
        @DisplayName("UNTIL 이 지난 반복은 창 안에 회차를 만들지 않는다")
        void 종료된_반복은_비어_있다() {
            String ics = wrap("""
                    DTSTART:20260602T010000Z
                    DTEND:20260602T020000Z
                    RRULE:FREQ=WEEKLY;BYDAY=TU;UNTIL=20260701T000000Z
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            assertThat(RecurrenceExpander.expand(event,
                    Instant.parse("2026-06-02T01:00:00Z"), Instant.parse("2026-06-02T02:00:00Z"),
                    SEOUL, Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z")))
                    .isEmpty();
        }

        @Test
        @DisplayName("반복이 없으면 원본 한 건 — 창과 겹칠 때만")
        void 단발은_그대로() {
            ICalParser.Component event = ICalParser.parseEvents(APPLE_SINGLE_EVENT).getFirst();
            Instant start = Instant.parse("2026-08-20T01:00:00Z");
            Instant end = Instant.parse("2026-08-20T02:00:00Z");

            assertThat(RecurrenceExpander.expand(event, start, end, SEOUL,
                    Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"))).hasSize(1);
            assertThat(RecurrenceExpander.expand(event, start, end, SEOUL,
                    Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z"))).isEmpty();
        }

        @Test
        @DisplayName("해석 못 한 규칙은 빈 목록이 아니라 원본 한 건으로 남는다")
        void 미지의_규칙은_사라지지_않는다() {
            String ics = wrap("""
                    DTSTART:20260820T010000Z
                    DTEND:20260820T020000Z
                    RRULE:FREQ=HOURLY;INTERVAL=6
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            // 못 펼치는 것과 일정이 없는 것은 다르다 — 후자로 보이면 그 시간 위에 계획이 얹힌다.
            assertThat(RecurrenceExpander.expand(event,
                    Instant.parse("2026-08-20T01:00:00Z"), Instant.parse("2026-08-20T02:00:00Z"),
                    SEOUL, Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z")))
                    .hasSize(1);
        }

        @Test
        @DisplayName("매월 31일 반복은 31일이 없는 달을 건너뛴다 — 말일로 당기지 않는다")
        void 없는_날짜는_만들지_않는다() {
            String ics = wrap("""
                    DTSTART:20260131T010000Z
                    DTEND:20260131T020000Z
                    RRULE:FREQ=MONTHLY
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            // 2월에는 31일이 없다. 28일로 당기면 없던 약속이 생긴다.
            assertThat(RecurrenceExpander.expand(event,
                    Instant.parse("2026-01-31T01:00:00Z"), Instant.parse("2026-01-31T02:00:00Z"),
                    SEOUL, Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-03-01T00:00:00Z")))
                    .isEmpty();
        }
    }

    private static String wrap(String eventBody) {
        return "BEGIN:VCALENDAR\nVERSION:2.0\nBEGIN:VEVENT\n" + eventBody + "END:VEVENT\nEND:VCALENDAR\n";
    }

    @Nested
    @DisplayName("DURATION — 종료를 길이로 적은 일정 (RFC5545 §3.3.6)")
    class DurationValues {

        @Test
        @DisplayName("시·분 표기를 읽는다")
        void 시분() {
            assertThat(ICalDateTime.parseDuration("PT1H30M")).isEqualTo(java.time.Duration.ofMinutes(90));
            assertThat(ICalDateTime.parseDuration("PT45M")).isEqualTo(java.time.Duration.ofMinutes(45));
            assertThat(ICalDateTime.parseDuration("P1DT2H")).isEqualTo(java.time.Duration.ofHours(26));
        }

        @Test
        @DisplayName("주 단위 P2W 도 읽는다 — Duration.parse 는 못 받는 표기다")
        void 주단위() {
            // RFC5545 는 허용하는데 ISO-8601 파서는 거부한다. 그대로 넘기면 해석 실패로 떨어져
            // 그 일정이 통째로 사라진다.
            assertThat(ICalDateTime.parseDuration("P2W")).isEqualTo(java.time.Duration.ofDays(14));
        }

        @Test
        @DisplayName("해석 불가·0·음수는 null — 길이로 쓸 수 없는 값이다")
        void 못쓰는_값() {
            assertThat(ICalDateTime.parseDuration(null)).isNull();
            assertThat(ICalDateTime.parseDuration("  ")).isNull();
            assertThat(ICalDateTime.parseDuration("1시간")).isNull();
            assertThat(ICalDateTime.parseDuration("PT0S")).isNull();
            // 음수는 알람 TRIGGER 용 표기다 — 일정 길이로 쓰면 종료가 시작보다 앞선다.
            assertThat(ICalDateTime.parseDuration("-PT15M")).isNull();
        }
    }
}
