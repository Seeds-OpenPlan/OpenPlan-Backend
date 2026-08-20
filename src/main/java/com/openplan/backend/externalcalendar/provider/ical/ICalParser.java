package com.openplan.backend.externalcalendar.provider.ical;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * iCalendar(RFC 5545) 본문에서 {@code VEVENT} 만 꺼낸다 (ST-B1-11 · 네이버 CalDAV).
 *
 * <p><b>왜 라이브러리를 안 쓰는가.</b> 우리가 쓰는 것은 VEVENT 의 여섯 필드뿐이고(UID·SUMMARY·DTSTART·
 * DTEND·RRULE·EXDATE), 나머지는 전부 버린다. 전체 규격을 다루는 의존성을 들이는 대신 필요한 만큼만
 * 읽는다 — 다만 <b>덜 읽어서 조용히 틀리는 것</b>이 이 클래스의 유일한 실패 모드이므로, 아래 두 함정을
 * 명시적으로 처리한다.
 *
 * <p><b>함정 ① — {@code VTIMEZONE} 안에도 {@code DTSTART} 와 {@code RRULE} 이 있다.</b> 네이버 응답에는
 * 이벤트마다 {@code VTIMEZONE} 이 붙어 오고, 그 안의 {@code STANDARD}/{@code DAYLIGHT} 하위 컴포넌트가
 * {@code DTSTART:19870510T000000} · {@code RRULE:FREQ=YEARLY;UNTIL=19880507T170000Z;BYMONTH=5;BYDAY=2SU}
 * 를 담고 있다(한국의 1987~88 서머타임). <b>컴포넌트 경계를 무시하고 줄 단위로 훑으면 1988년 서머타임
 * 규칙이 일정의 반복 규칙으로 둔갑한다</b> — 실제로 실측 응답 143건에서 RRULE 17건이 전부 이것이었다.
 * 그래서 {@code BEGIN}/{@code END} 로 깊이를 세고 <b>{@code VEVENT} 안에 있을 때만</b> 속성을 담는다.
 *
 * <p><b>함정 ② — 줄 접힘(line folding).</b> RFC 5545 는 75옥텟을 넘는 줄을 CRLF + 공백/탭으로 잇는다.
 * 펼치지 않고 읽으면 긴 제목이 중간에서 잘리고, 잘린 뒷부분이 <b>속성 이름 없는 줄</b>이 되어 조용히 버려진다.
 */
public final class ICalParser {

    private ICalParser() {
    }

    /** 컴포넌트 하나 = 속성 이름 → (파라미터 문자열, 값). 같은 이름이 여러 번이면(EXDATE) 값을 모은다. */
    public record Component(Map<String, List<Property>> properties) {

        public Property first(String name) {
            List<Property> found = properties.get(name);
            return found == null || found.isEmpty() ? null : found.getFirst();
        }

        public List<Property> all(String name) {
            return properties.getOrDefault(name, List.of());
        }

        public String value(String name) {
            Property p = first(name);
            return p == null ? null : p.value();
        }
    }

    /**
     * 속성 한 줄.
     *
     * @param params {@code ;} 뒤 파라미터 원문 — 예: {@code TZID=Asia/Seoul} · {@code VALUE=DATE}
     * @param value  {@code :} 뒤 값(이스케이프 해제 완료)
     */
    public record Property(String params, String value) {

        /** 파라미터 값 조회 — 없으면 null. 대소문자를 가리지 않는다(제공자마다 표기가 다르다). */
        public String param(String key) {
            if (params == null || params.isEmpty()) {
                return null;
            }
            for (String token : params.split(";")) {
                int eq = token.indexOf('=');
                if (eq > 0 && token.substring(0, eq).trim().equalsIgnoreCase(key)) {
                    return token.substring(eq + 1).trim();
                }
            }
            return null;
        }
    }

    /**
     * 본문에서 {@code VEVENT} 컴포넌트를 순서대로 꺼낸다.
     *
     * <p>{@code VEVENT} 가 아닌 컴포넌트({@code VTIMEZONE}·{@code VALARM}·{@code VTODO})는 통째로 건너뛴다.
     * {@code VALARM} 은 {@code VEVENT} <b>안에</b> 중첩되므로 깊이로 구분한다 — 알람의 {@code TRIGGER} 가
     * 일정 속성으로 섞이면 안 된다.
     */
    public static List<Component> parseEvents(String text) {
        List<Component> events = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return events;
        }

        Map<String, List<Property>> current = null;
        int depthInsideEvent = 0;

        for (String line : unfold(text)) {
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase();

            if (upper.startsWith("BEGIN:")) {
                String component = line.substring(6).trim();
                if (current == null) {
                    if ("VEVENT".equalsIgnoreCase(component)) {
                        current = new LinkedHashMap<>();
                        depthInsideEvent = 0;
                    }
                } else {
                    // VEVENT 안의 중첩(VALARM) — 속성을 담지 않는다.
                    depthInsideEvent++;
                }
                continue;
            }
            if (upper.startsWith("END:")) {
                String component = line.substring(4).trim();
                if (current != null) {
                    if (depthInsideEvent > 0) {
                        depthInsideEvent--;
                    } else if ("VEVENT".equalsIgnoreCase(component)) {
                        events.add(new Component(current));
                        current = null;
                    }
                }
                continue;
            }
            if (current == null || depthInsideEvent > 0) {
                // VEVENT 밖(= VTIMEZONE 포함) 이거나 VALARM 안이다. 여기서 걸러야 1988년 서머타임
                // RRULE 이 일정 반복으로 새지 않는다.
                continue;
            }

            int colon = indexOfValueColon(line);
            if (colon < 0) {
                continue;
            }
            String head = line.substring(0, colon);
            String value = unescape(line.substring(colon + 1));

            int semi = head.indexOf(';');
            String name = (semi < 0 ? head : head.substring(0, semi)).trim().toUpperCase();
            String params = semi < 0 ? "" : head.substring(semi + 1);

            current.computeIfAbsent(name, k -> new ArrayList<>()).add(new Property(params, value));
        }
        return events;
    }

    /**
     * 접힌 줄을 펼친다 — 다음 줄이 공백이나 탭으로 시작하면 앞 줄의 연속이다.
     *
     * <p>CRLF 가 정본이지만 LF 만 오는 구현도 있어 둘 다 받는다.
     */
    private static List<String> unfold(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                buffer.append(raw, 1, raw.length());
                continue;
            }
            if (buffer.length() > 0) {
                out.add(buffer.toString());
            }
            buffer.setLength(0);
            buffer.append(raw);
        }
        if (buffer.length() > 0) {
            out.add(buffer.toString());
        }
        return out;
    }

    /**
     * 값을 여는 {@code :} 의 위치.
     *
     * <p>파라미터 값이 따옴표로 묶이면 그 안에 {@code :} 가 들어갈 수 있다
     * ({@code DTSTART;TZID="GMT+09:00":...}). 따옴표 밖의 첫 {@code :} 를 찾는다 — 단순히 앞에서부터
     * 세면 시각 문자열이 잘린다.
     */
    private static int indexOfValueColon(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ':' && !quoted) {
                return i;
            }
        }
        return -1;
    }

    /** RFC 5545 TEXT 이스케이프 해제. 제목에 쉼표·세미콜론이 들어가는 경우가 흔하다. */
    private static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case 'n', 'N' -> sb.append('\n');
                    case ',', ';', '\\' -> sb.append(next);
                    default -> sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
