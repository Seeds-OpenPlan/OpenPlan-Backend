package com.openplan.backend.externalcalendar.provider.ical;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code RRULE} 을 조회 구간 안의 실제 발생으로 펼친다 (ST-B1-11 · 네이버 CalDAV).
 *
 * <p><b>왜 서버가 아니라 우리가 펼치는가.</b> CalDAV 표준은 서버가 펼쳐 주는 {@code <C:expand>} 를
 * 정의하지만, <b>네이버는 그 요소를 받고도 무시한다</b> — 붙이든 안 붙이든 응답이 완전히 같고
 * {@code RRULE} 이 그대로 남아 온다(2026-08-20 실측: 마스터 1건 · DTSTART 가 조회 구간 밖의 원래 날짜).
 * 오류도 경고도 없으므로, 서버가 해 줄 것이라 믿고 짜면 <b>매주 회의가 엉뚱한 날짜의 단발 일정 하나로</b>
 * 보인다.
 *
 * <p><b>다행인 것 하나.</b> 네이버의 {@code time-range} 필터는 반복을 고려해 매칭한다 — 6월에 시작한
 * 매주 일정이 8월 조회 목록에 잡힌다(실측). 그래서 <b>일정을 놓치지는 않고</b>, 받은 마스터를 여기서
 * 펼치기만 하면 된다.
 *
 * <p><b>벽시계 시각을 보존한다.</b> 발생 계산을 {@link Instant} 산술로 하면 서머타임 경계에서 시각이
 * 한 시간 밀린다. 한국은 지금 서머타임이 없지만 사용자의 다른 지역 캘린더가 섞일 수 있으므로,
 * 계산은 {@link ZonedDateTime} 으로 하고 마지막에만 {@link Instant} 로 바꾼다.
 */
public final class RecurrenceExpander {

    private static final Logger log = LoggerFactory.getLogger(RecurrenceExpander.class);

    /**
     * 안전 상한 — 무한 반복이 조회 구간보다 훨씬 앞에서 시작할 때 순회가 끝나지 않는 것을 막는다.
     * 2010년부터의 매일 반복도 6천 회 남짓이라 실사용에서는 닿지 않는다.
     */
    private static final int MAX_ITERATIONS = 20_000;

    /** 한 일정이 만들어 낼 수 있는 발생 수 상한 — 조회 구간이 넓어도 결과가 폭발하지 않게. */
    private static final int MAX_OCCURRENCES = 1_000;

    private RecurrenceExpander() {
    }

    /** 발생 한 건 — 시작·종료 쌍. */
    public record Occurrence(Instant startAt, Instant endAt) {
    }

    /**
     * 구간 {@code [from, to)} 와 겹치는 발생을 모두 돌려준다.
     *
     * <p>{@code RRULE} 이 없으면 원본 한 건만(구간과 겹칠 때). 해석하지 못하는 규칙을 만나면
     * <b>빈 목록이 아니라 원본 한 건</b>을 돌려주고 경고를 남긴다 — 못 펼치는 것과 일정이 없는 것은
     * 다르고, 후자로 보이면 그 시간 위에 계획이 얹힌다.
     */
    public static List<Occurrence> expand(ICalParser.Component event, Instant start, Instant end,
                                          ZoneId zone, Instant from, Instant to) {
        if (start == null) {
            return List.of();
        }
        Duration length = (end != null && end.isAfter(start)) ? Duration.between(start, end) : Duration.ZERO;
        ICalParser.Property rrule = event.first("RRULE");

        if (rrule == null || rrule.value() == null || rrule.value().isBlank()) {
            return overlaps(start, start.plus(length), from, to)
                    ? List.of(new Occurrence(start, start.plus(length)))
                    : List.of();
        }

        Map<String, String> parts = parseParts(rrule.value());
        String freq = parts.getOrDefault("FREQ", "").toUpperCase(Locale.ROOT);
        int interval = intOrDefault(parts.get("INTERVAL"), 1);
        if (interval < 1) {
            interval = 1;
        }
        Integer count = parts.containsKey("COUNT") ? intOrDefault(parts.get("COUNT"), 0) : null;
        Instant until = ICalDateTime.parseDateOrDateTime(parts.get("UNTIL"), null, zone);
        Set<Instant> excluded = exclusions(event, zone);

        ZonedDateTime origin = start.atZone(zone);
        List<Occurrence> out = new ArrayList<>();

        List<ZonedDateTime> candidates = switch (freq) {
            case "DAILY" -> byPeriod(origin, interval, ChronoStep.DAY, count, until, to);
            case "WEEKLY" -> weekly(origin, interval, parts.get("BYDAY"), parts.get("WKST"), count, until, to);
            case "MONTHLY" -> byPeriod(origin, interval, ChronoStep.MONTH, count, until, to);
            case "YEARLY" -> byPeriod(origin, interval, ChronoStep.YEAR, count, until, to);
            default -> null;
        };

        if (candidates == null) {
            log.warn("해석하지 못한 반복 규칙 — 원본 한 건만 후보로 둔다: rrule={}", rrule.value());
            return overlaps(start, start.plus(length), from, to)
                    ? List.of(new Occurrence(start, start.plus(length)))
                    : List.of();
        }

        for (ZonedDateTime candidate : candidates) {
            Instant occurrenceStart = candidate.toInstant();
            if (excluded.contains(occurrenceStart)) {
                continue;
            }
            Instant occurrenceEnd = occurrenceStart.plus(length);
            if (overlaps(occurrenceStart, occurrenceEnd, from, to)) {
                out.add(new Occurrence(occurrenceStart, occurrenceEnd));
                if (out.size() >= MAX_OCCURRENCES) {
                    log.warn("반복 전개가 상한에 도달했다 — 이후 발생은 보이지 않는다: rrule={}", rrule.value());
                    break;
                }
            }
        }
        return out;
    }

    private enum ChronoStep { DAY, MONTH, YEAR }

    /**
     * 일·월·년 주기 — 원점에서 {@code interval} 씩 더해 나간다.
     *
     * <p>월/년 주기에서 <b>존재하지 않는 날짜는 건너뛴다</b>(31일 반복의 2월). {@code plusMonths} 는
     * 말일로 당겨 주는데, 그러면 31일 일정이 2월 28일에 생겨 <b>없던 일정이 만들어진다.</b>
     */
    private static List<ZonedDateTime> byPeriod(ZonedDateTime origin, int interval, ChronoStep step,
                                                Integer count, Instant until, Instant to) {
        List<ZonedDateTime> out = new ArrayList<>();
        int dayOfMonth = origin.getDayOfMonth();
        int produced = 0;

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            ZonedDateTime candidate = switch (step) {
                case DAY -> origin.plusDays((long) i * interval);
                case MONTH -> origin.plusMonths((long) i * interval);
                case YEAR -> origin.plusYears((long) i * interval);
            };
            if (step != ChronoStep.DAY && candidate.getDayOfMonth() != dayOfMonth) {
                // 말일로 당겨진 것 — 그 주기에는 해당 날짜가 없다.
                continue;
            }
            produced++;
            if (count != null && produced > count) {
                break;
            }
            if (until != null && candidate.toInstant().isAfter(until)) {
                break;
            }
            if (!candidate.toInstant().isBefore(to)) {
                break;
            }
            out.add(candidate);
        }
        return out;
    }

    /**
     * 주 단위 반복. {@code BYDAY} 가 있으면 그 요일들, 없으면 원점의 요일.
     *
     * <p>{@code BYDAY} 의 {@code 2SU} 같은 순서 접두는 <b>월 단위에서만 의미가 있다</b> — 주 단위에서는
     * 요일만 취한다. (네이버 VTIMEZONE 의 서머타임 규칙이 정확히 이 모양인데, 그것은 파서가 이미 걸러
     * 여기까지 오지 않는다.)
     */
    private static List<ZonedDateTime> weekly(ZonedDateTime origin, int interval, String byDay, String weekStart,
                                              Integer count, Instant until, Instant to) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        if (byDay != null && !byDay.isBlank()) {
            for (String token : byDay.split(",")) {
                DayOfWeek day = dayOfWeek(token);
                if (day != null) {
                    days.add(day);
                }
            }
        }
        if (days.isEmpty()) {
            days.add(origin.getDayOfWeek());
        }

        DayOfWeek wkst = weekStart == null ? DayOfWeek.MONDAY : dayOfWeek(weekStart);
        ZonedDateTime anchor = origin.with(TemporalAdjusters.previousOrSame(
                wkst == null ? DayOfWeek.MONDAY : wkst));

        List<ZonedDateTime> out = new ArrayList<>();
        int produced = 0;

        outer:
        for (int week = 0; week < MAX_ITERATIONS; week++) {
            ZonedDateTime weekStartAt = anchor.plusWeeks((long) week * interval);
            for (DayOfWeek day : days) {
                ZonedDateTime candidate = weekStartAt.with(TemporalAdjusters.nextOrSame(day));
                if (candidate.isBefore(origin)) {
                    // 원점이 속한 주에서 원점보다 앞선 요일 — 반복은 원점부터 시작한다.
                    continue;
                }
                produced++;
                if (count != null && produced > count) {
                    break outer;
                }
                if (until != null && candidate.toInstant().isAfter(until)) {
                    break outer;
                }
                if (!candidate.toInstant().isBefore(to)) {
                    break outer;
                }
                out.add(candidate);
            }
        }
        return out;
    }

    /** {@code EXDATE} — 사용자가 그 회차를 지웠다는 뜻이다. 되살리면 없는 약속이 생긴다. */
    private static Set<Instant> exclusions(ICalParser.Component event, ZoneId zone) {
        Set<Instant> out = new HashSet<>();
        for (ICalParser.Property property : event.all("EXDATE")) {
            String tzid = property.param("TZID");
            for (String token : property.value().split(",")) {
                Instant parsed = ICalDateTime.parseDateOrDateTime(token, tzid, zone);
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        }
        return out;
    }

    private static boolean overlaps(Instant start, Instant end, Instant from, Instant to) {
        // 길이 0 인 일정은 시작 시각이 구간 안에 있으면 겹치는 것으로 본다.
        Instant effectiveEnd = end.isAfter(start) ? end : start.plusMillis(1);
        return start.isBefore(to) && effectiveEnd.isAfter(from);
    }

    private static Map<String, String> parseParts(String rrule) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String token : rrule.split(";")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                parts.put(token.substring(0, eq).trim().toUpperCase(Locale.ROOT), token.substring(eq + 1).trim());
            }
        }
        return parts;
    }

    private static DayOfWeek dayOfWeek(String token) {
        if (token == null) {
            return null;
        }
        String code = token.trim().toUpperCase(Locale.ROOT);
        // 순서 접두(예: 2SU · -1FR) 제거 — 주 단위에서는 요일만 쓴다.
        code = code.replaceAll("^[+-]?\\d+", "");
        return switch (code) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    private static int intOrDefault(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
