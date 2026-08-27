package com.openplan.backend.externalcalendar.provider.ical;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * iCalendar 날짜·시각 값을 {@link Instant} 로 (ST-B1-11 · 애플 CalDAV).
 *
 * <p><b>카카오 것을 재사용할 수 없는 이유.</b> 카카오는 {@code 20260820T010000Z} 처럼 UTC 로 주지만,
 * 애플은 {@code DTSTART;TZID=Asia/Seoul:20260820T100000} 로 <b>지역 시각 + 별도 TZID</b> 를 준다
 * (2026-08-20 실측). 값만 보고 파싱하면 KST 시각을 UTC 로 읽어 <b>모든 일정이 9시간 어긋난다</b> —
 * 예외도 로그도 없이 그럴듯한 시각이 나오므로 눈으로는 못 잡는다.
 *
 * <p>세 가지 형태를 받는다.
 * <ul>
 *   <li>{@code ...Z} — UTC. 그대로 {@link Instant}</li>
 *   <li>{@code TZID=<zone>} 파라미터 + 시각 — 그 지역의 벽시계 시각</li>
 *   <li>파라미터도 {@code Z} 도 없음(floating) — 아래 {@code fallbackZone} 으로 해석</li>
 * </ul>
 *
 * <p><b>종일 일정({@code VALUE=DATE})은 {@code null} 을 돌려준다.</b> 시각이 없어 고정 일정(요일+시분)으로
 * 옮길 자리가 없고, 하루를 통째로 채우면 의도하지 않은 차단이 된다 — 구글·카카오 어댑터와 같은 판단이다.
 */
public final class ICalDateTime {

    private static final DateTimeFormatter BASIC_LOCAL =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter BASIC_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");

    /** RFC5545 는 주 단위({@code P2W})를 허용하지만 {@link Duration#parse} 는 못 받는다 — 여기서 일수로 바꾼다. */
    private static final Pattern WEEK_DURATION = Pattern.compile("P(\\d+)W");

    private ICalDateTime() {
    }

    /**
     * 속성 하나를 시각으로. 종일이거나 해석 불가면 {@code null}.
     *
     * @param fallbackZone {@code TZID} 도 {@code Z} 도 없을 때 쓸 지역. floating 시각은 "보는 사람의
     *                     현지 시각"이라는 뜻이라 서버가 임의로 UTC 로 읽으면 안 된다.
     */
    public static Instant parse(ICalParser.Property property, ZoneId fallbackZone) {
        if (property == null) {
            return null;
        }
        String value = property.value();
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();

        // 종일 — VALUE=DATE 이거나 날짜만 8자리.
        if ("DATE".equalsIgnoreCase(property.param("VALUE")) || value.length() == 8) {
            return null;
        }

        if (value.endsWith("Z")) {
            try {
                return LocalDateTime.parse(value.substring(0, value.length() - 1), BASIC_LOCAL)
                        .toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        ZoneId zone = zoneOf(property.param("TZID"), fallbackZone);
        try {
            return LocalDateTime.parse(value, BASIC_LOCAL).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 종일 여부 — 시각이 없어 후보에서 거를 대상인지. */
    public static boolean isAllDay(ICalParser.Property property) {
        if (property == null) {
            return false;
        }
        String value = property.value();
        return "DATE".equalsIgnoreCase(property.param("VALUE"))
                || (value != null && value.trim().length() == 8);
    }

    /**
     * {@code DURATION} 값을 길이로 (RFC5545 §3.3.6). 해석 불가·음수·0 이면 {@code null}.
     *
     * <p><b>왜 필요한가.</b> VEVENT 는 종료를 {@code DTEND} 로 주기도 하고 {@code DURATION} 으로 주기도
     * 한다 — 둘 다 표준이고, 서드파티 CalDAV 클라이언트가 만든 일정에는 후자가 드물지 않다. 이 값을
     * 못 읽으면 길이가 0 이 되어 그 일정은 <b>흔적 없이 후보 목록에서 사라진다</b>(2026-08-27 리뷰 지적).
     *
     * <p>{@link Duration#parse} 는 ISO-8601 이라 {@code P1DT2H30M}·{@code PT45M} 은 그대로 받지만
     * <b>주 단위 {@code P2W} 는 못 받는다.</b> 주는 여기서 일수로 바꿔 넘긴다.
     *
     * <p>음수({@code -PT15M})는 알람 {@code TRIGGER} 용 표기이지 일정 길이가 아니므로 받지 않는다 —
     * 받으면 종료가 시작보다 앞서는 구간이 만들어진다.
     */
    public static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("+")) {
            value = value.substring(1);
        }
        Matcher weeks = WEEK_DURATION.matcher(value);
        if (weeks.matches()) {
            long days = Long.parseLong(weeks.group(1)) * 7L;
            return days > 0 ? Duration.ofDays(days) : null;
        }
        try {
            Duration parsed = Duration.parse(value);
            return (parsed.isZero() || parsed.isNegative()) ? null : parsed;
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
    }

    /** {@code EXDATE} 등에 쓰는 날짜 파싱 — 종일 제외일도 비교 대상이 되므로 날짜형을 함께 받는다. */
    public static Instant parseDateOrDateTime(String raw, String tzid, ZoneId fallbackZone) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        raw = raw.trim();
        try {
            if (raw.endsWith("Z")) {
                return LocalDateTime.parse(raw.substring(0, raw.length() - 1), BASIC_LOCAL)
                        .toInstant(ZoneOffset.UTC);
            }
            if (raw.length() == 8) {
                return LocalDate.parse(raw, BASIC_DATE)
                        .atStartOfDay(zoneOf(tzid, fallbackZone)).toInstant();
            }
            return LocalDateTime.parse(raw, BASIC_LOCAL).atZone(zoneOf(tzid, fallbackZone)).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * {@code TZID} 문자열 → {@link ZoneId}.
     *
     * <p>알 수 없는 지역 이름이면 <b>예외를 밖으로 내보내지 않고</b> fallback 을 쓴다. 여기서 터지면
     * 일정 한 건의 지역 표기 때문에 <b>조회 전체가 502</b> 가 된다 — 한 건이 어긋나는 편이 낫다.
     */
    private static ZoneId zoneOf(String tzid, ZoneId fallbackZone) {
        if (tzid == null || tzid.isBlank()) {
            return fallbackZone;
        }
        String cleaned = tzid.trim();
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            return ZoneId.of(cleaned);
        } catch (Exception e) {
            return fallbackZone;
        }
    }
}
