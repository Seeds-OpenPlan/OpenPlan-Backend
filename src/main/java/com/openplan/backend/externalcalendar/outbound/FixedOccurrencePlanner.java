package com.openplan.backend.externalcalendar.outbound;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 고정 일정(주간 반복 패턴)을 <b>2개월치 회차</b>로 펼친다 (#69 계획 D1).
 *
 * <p>순수 계산이다 — 저장소·시계·스프링을 모르고, 같은 입력이면 같은 출력이다. 규칙 엔진이
 * {@code C-1(결정성)} 을 지키는 것과 같은 이유다: 「왜 이 날짜가 나왔나」 를 재현해 볼 수 있어야
 * 사용자 캘린더에 잘못 쓴 원인을 찾을 수 있다.
 *
 * <p><b>창은 굴러간다.</b> 시작점이 «오늘» 이므로 동기화가 돌 때마다 창이 앞으로 밀리고, 새로
 * 들어온 회차가 다음 회차로 잡힌다 — 별도 연장 배치가 필요 없다(계획 D2).
 */
public final class FixedOccurrencePlanner {

    /** 얼마나 앞까지 내보낼 것인가. 사용자 요구가 «2달치» 다. */
    public static final Duration HORIZON = Duration.ofDays(62);

    private FixedOccurrencePlanner() {
    }

    /** 펼쳐진 회차 하나 — 날짜와 그 날의 절대 시각. */
    public record Occurrence(LocalDate date, Instant startAt, Instant endAt) {
    }

    /** 펼칠 재료. {@code FixedSchedule} 엔티티에 게터가 없어 투영으로 받는다(PlanSnapshotAssembler 와 같은 관례). */
    public record Pattern(UUID fixedScheduleId, String title, java.time.DayOfWeek weekday,
                          LocalTime startTime, LocalTime endTime,
                          LocalDate startDate, LocalDate endDate) {
    }

    /**
     * {@code today} 부터 {@link #HORIZON} 까지 이 패턴이 실제로 열리는 날들.
     *
     * <p>걸러내는 것 셋 —
     * <ul>
     *   <li>패턴의 기간({@code start_date}·{@code end_date}) 밖</li>
     *   <li>주차 예외가 걸린 주(PLAN-33) — 그 주만 안 열린다</li>
     *   <li>오늘 이전 — 지난 회차를 새로 만들지 않는다</li>
     * </ul>
     *
     * @param exceptionWeekStarts 예외가 걸린 주의 시작일. 사용자 주 시작 요일 기준으로 저장돼 있다.
     * @param weekStartsOn        그 사용자의 주 시작 요일 — 예외 주를 같은 기준으로 계산해야 어긋나지 않는다.
     */
    public static List<Occurrence> expand(Pattern pattern, LocalDate today, ZoneId zone,
                                          Set<LocalDate> exceptionWeekStarts,
                                          java.time.DayOfWeek weekStartsOn) {
        LocalDate horizonEnd = today.plusDays(HORIZON.toDays());
        LocalDate from = pattern.startDate() != null && pattern.startDate().isAfter(today)
                ? pattern.startDate() : today;
        LocalDate to = pattern.endDate() != null && pattern.endDate().isBefore(horizonEnd)
                ? pattern.endDate() : horizonEnd;

        List<Occurrence> result = new ArrayList<>();
        if (from.isAfter(to)) {
            return result;   // 이미 끝난 패턴 — 내보낼 회차가 없다.
        }
        LocalDate cursor = from.with(TemporalAdjusters.nextOrSame(pattern.weekday()));
        while (!cursor.isAfter(to)) {
            LocalDate weekStart = cursor.with(TemporalAdjusters.previousOrSame(weekStartsOn));
            if (!exceptionWeekStarts.contains(weekStart)) {
                result.add(new Occurrence(cursor,
                        cursor.atTime(pattern.startTime()).atZone(zone).toInstant(),
                        cursor.atTime(pattern.endTime()).atZone(zone).toInstant()));
            }
            cursor = cursor.plusWeeks(1);
        }
        return result;
    }
}
