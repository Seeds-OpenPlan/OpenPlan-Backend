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
        @DisplayName("BYDAY 토큰 순서가 시간 역순이어도 이른 요일이 유실되지 않는다")
        void 요일_토큰_순서가_역순이어도_유실되지_않는다() {
            // BYDAY=FR,MO — 토큰은 금요일이 먼저지만 그 주에서 시간상 먼저 오는 것은 월요일이다.
            // 조회 창을 그 주 수요일에서 끊으면, 토큰 순서대로 도는 구현은 금요일 후보가 창을
            // 넘는 순간 전체를 끊어 버려 창 안에 있는 월요일(원점 자신)을 평가조차 하지 않았다.
            String ics = wrap("""
                    UID:weekly-byday-order@test
                    DTSTART:20260817T010000Z
                    DTEND:20260817T020000Z
                    RRULE:FREQ=WEEKLY;BYDAY=FR,MO
                    SUMMARY:월금 스터디
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            List<RecurrenceExpander.Occurrence> occurrences = RecurrenceExpander.expand(
                    event, Instant.parse("2026-08-17T01:00:00Z"), Instant.parse("2026-08-17T02:00:00Z"),
                    SEOUL, Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-19T00:00:00Z"));

            assertThat(occurrences).hasSize(1);
            assertThat(occurrences.getFirst().startAt()).isEqualTo(Instant.parse("2026-08-17T01:00:00Z"));
        }

        @Test
        @DisplayName("여러 요일 반복은 토큰 순서가 아니라 시간 순으로 나온다")
        void 여러_요일은_시간순으로_나온다() {
            String ics = wrap("""
                    UID:weekly-byday-multi@test
                    DTSTART:20260817T010000Z
                    DTEND:20260817T020000Z
                    RRULE:FREQ=WEEKLY;BYDAY=FR,MO
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            List<RecurrenceExpander.Occurrence> occurrences = RecurrenceExpander.expand(
                    event, Instant.parse("2026-08-17T01:00:00Z"), Instant.parse("2026-08-17T02:00:00Z"),
                    SEOUL, Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z"));

            // 월(8/17) → 금(8/21). 토큰 순서(FR,MO)를 그대로 따르면 순서가 뒤집힌다.
            assertThat(occurrences).hasSize(2);
            assertThat(occurrences.get(0).startAt()).isEqualTo(Instant.parse("2026-08-17T01:00:00Z"));
            assertThat(occurrences.get(1).startAt()).isEqualTo(Instant.parse("2026-08-21T01:00:00Z"));
        }

        @Test
        @DisplayName("월 단위 BYDAY 는 틀린 날짜를 만드는 대신 한 건으로 떨어진다")
        void 월단위_BYDAY_는_조용히_틀리지_않는다() {
            // FREQ=MONTHLY;BYDAY=1MO 는 "매월 첫째 월요일" 이다. byPeriod 는 원점의 "일" 만
            // 반복하므로 그대로 두면 2/5(목)·3/5(목)·4/5(일) 처럼 전혀 다른 요일에 일정이 생긴다.
            // FREQ 를 스위치가 알아보기 때문에 폴백도 안 타고 경고 없이 틀린다 —
            // 없던 일정을 만드느니 한 건만 두고 경고를 남긴다.
            String ics = wrap("""
                    UID:monthly-byday@test
                    DTSTART:20260105T010000Z
                    DTEND:20260105T020000Z
                    RRULE:FREQ=MONTHLY;BYDAY=1MO
                    SUMMARY:매월 첫째 월요일 회의
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            List<RecurrenceExpander.Occurrence> occurrences = RecurrenceExpander.expand(
                    event, Instant.parse("2026-01-05T01:00:00Z"), Instant.parse("2026-01-05T02:00:00Z"),
                    SEOUL, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-05-01T00:00:00Z"));

            // 원본 한 건만. 2/5·3/5·4/5 같은 엉뚱한 날짜가 섞이면 안 된다.
            assertThat(occurrences).hasSize(1);
            assertThat(occurrences.getFirst().startAt()).isEqualTo(Instant.parse("2026-01-05T01:00:00Z"));
        }

        @Test
        @DisplayName("BYDAY 없는 월 단위 반복은 그대로 매달 같은 날에 선다")
        void 월단위_BYDAY_없으면_정상_전개() {
            String ics = wrap("""
                    UID:monthly-plain@test
                    DTSTART:20260105T010000Z
                    DTEND:20260105T020000Z
                    RRULE:FREQ=MONTHLY
                    """);
            ICalParser.Component event = ICalParser.parseEvents(ics).getFirst();

            List<RecurrenceExpander.Occurrence> occurrences = RecurrenceExpander.expand(
                    event, Instant.parse("2026-01-05T01:00:00Z"), Instant.parse("2026-01-05T02:00:00Z"),
                    SEOUL, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-04-01T00:00:00Z"));

            assertThat(occurrences).hasSize(3);   // 1/5 · 2/5 · 3/5
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
}
