package com.openplan.backend.externalcalendar.outbound;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고정 일정을 회차로 펼치는 계산 (#69 D1).
 *
 * <p>🔴 이 계산이 틀리면 <b>사용자의 실제 캘린더에 없는 일정이 생기거나 있어야 할 것이 빠진다.</b>
 * 순수 함수라 여기서 전부 확인할 수 있다 — 외부 호출이 끼기 전에 잡는 것이 가장 싸다.
 */
class FixedOccurrencePlannerTest {

    private static final UUID FS = UUID.randomUUID();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);   // 목요일

    private static FixedOccurrencePlanner.Pattern tuesday(LocalDate start, LocalDate end) {
        return new FixedOccurrencePlanner.Pattern(FS, "알고리즘 스터디", DayOfWeek.TUESDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 30), start, end);
    }

    @Test
    @DisplayName("2개월 창 안의 화요일을 모두 펼친다")
    void 창_안의_회차를_펼친다() {
        List<FixedOccurrencePlanner.Occurrence> out =
                FixedOccurrencePlanner.expand(tuesday(null, null), TODAY, SEOUL, Set.of(), DayOfWeek.MONDAY);

        assertThat(out).isNotEmpty();
        assertThat(out).allMatch(o -> o.date().getDayOfWeek() == DayOfWeek.TUESDAY);
        // 62일 창이면 화요일은 8~9번 온다.
        assertThat(out.size()).isBetween(8, 9);
        assertThat(out.getFirst().date()).isEqualTo(LocalDate.of(2026, 9, 22));
    }

    @Test
    @DisplayName("🔴 오늘 이전은 만들지 않는다 — 지난 회차를 외부에 새로 만들면 안 된다")
    void 지난_회차는_만들지_않는다() {
        List<FixedOccurrencePlanner.Occurrence> out = FixedOccurrencePlanner.expand(
                tuesday(LocalDate.of(2026, 1, 1), null), TODAY, SEOUL, Set.of(), DayOfWeek.MONDAY);

        assertThat(out).allMatch(o -> !o.date().isBefore(TODAY));
    }

    @Test
    @DisplayName("🔴 주차 예외가 걸린 주는 빠진다 — 이것이 EXDATE 대신 «그 일정 하나 삭제» 가 되는 지점이다")
    void 주차_예외는_빠진다() {
        LocalDate excluded = LocalDate.of(2026, 9, 22);              // 그 화요일
        LocalDate weekStart = LocalDate.of(2026, 9, 21);             // 그 주 월요일

        List<FixedOccurrencePlanner.Occurrence> out = FixedOccurrencePlanner.expand(
                tuesday(null, null), TODAY, SEOUL, Set.of(weekStart), DayOfWeek.MONDAY);

        assertThat(out).extracting(FixedOccurrencePlanner.Occurrence::date).doesNotContain(excluded);
        assertThat(out).isNotEmpty();
    }

    @Test
    @DisplayName("패턴의 종료일 이후는 만들지 않는다")
    void 종료일_이후는_만들지_않는다() {
        LocalDate end = LocalDate.of(2026, 10, 6);

        List<FixedOccurrencePlanner.Occurrence> out = FixedOccurrencePlanner.expand(
                tuesday(null, end), TODAY, SEOUL, Set.of(), DayOfWeek.MONDAY);

        assertThat(out).allMatch(o -> !o.date().isAfter(end));
        assertThat(out).hasSize(3);   // 9/22 · 9/29 · 10/6
    }

    @Test
    @DisplayName("이미 끝난 패턴은 아무것도 내보내지 않는다")
    void 끝난_패턴은_비어_있다() {
        List<FixedOccurrencePlanner.Occurrence> out = FixedOccurrencePlanner.expand(
                tuesday(null, LocalDate.of(2026, 8, 1)), TODAY, SEOUL, Set.of(), DayOfWeek.MONDAY);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("🔴 시각은 사용자 시간대로 해석한다 — UTC 로 읽으면 9시가 0시가 된다")
    void 시각은_사용자_시간대다() {
        List<FixedOccurrencePlanner.Occurrence> out =
                FixedOccurrencePlanner.expand(tuesday(null, null), TODAY, SEOUL, Set.of(), DayOfWeek.MONDAY);

        // 2026-09-22 09:00 KST = 2026-09-22 00:00Z
        assertThat(out.getFirst().startAt()).isEqualTo(Instant.parse("2026-09-22T00:00:00Z"));
        assertThat(out.getFirst().endAt()).isEqualTo(Instant.parse("2026-09-22T01:30:00Z"));
    }

    @Test
    @DisplayName("주 시작 요일이 일요일인 사용자도 예외 주가 어긋나지 않는다")
    void 주_시작_요일을_따른다() {
        // 주 시작이 일요일이면 9/22(화)가 속한 주의 시작은 9/20(일)이다.
        List<FixedOccurrencePlanner.Occurrence> out = FixedOccurrencePlanner.expand(
                tuesday(null, null), TODAY, SEOUL, Set.of(LocalDate.of(2026, 9, 20)), DayOfWeek.SUNDAY);

        assertThat(out).extracting(FixedOccurrencePlanner.Occurrence::date)
                .doesNotContain(LocalDate.of(2026, 9, 22));
    }
}
