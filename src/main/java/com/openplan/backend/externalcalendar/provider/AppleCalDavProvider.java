package com.openplan.backend.externalcalendar.provider;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.provider.ical.ICalDateTime;
import com.openplan.backend.externalcalendar.provider.ical.ICalParser;
import com.openplan.backend.externalcalendar.provider.ical.RecurrenceExpander;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 애플 캘린더 어댑터 — CalDAV (ST-B1-11 · ONB-07~09 · FIX-13~17).
 *
 * <p><b>왜 CalDAV 인가.</b> 애플은 캘린더용 공개 REST API 를 제공하지 않는다. iCloud 캘린더에
 * 접근하는 문서화된 경로는 CalDAV({@code caldav.icloud.com}) 뿐이고, 인증은 OAuth 가 아니라
 * <b>Apple ID + 앱 암호(app-specific password)</b> 의 HTTP Basic 이다. 그래서 이 어댑터만
 * {@link ExternalCalendarProvider.CalendarAuthModel#CALDAV_BASIC} 이다.
 *
 * <p><b>발견은 표준대로 한다</b> — {@code current-user-principal} → {@code calendar-home-set} →
 * 모음 순회. 경로 모양을 상수로 박지 않는 이유는, iCloud 가 계정마다 다른 샤드 호스트와
 * 숫자 ID 경로를 쓰기 때문이다. 박아 두면 계정에 따라 전부 404 가 된다.
 *
 * <p><b>표준대로 짜면 조용히 틀리는 두 곳</b> — 둘 다 CalDAV 서버 전반에서 흔하고, 이 구현은
 * 안전한 쪽을 택한다:
 * <ol>
 *   <li>{@code calendar-query} 응답에 {@code calendar-data} 가 안 실려 올 수 있다. 상태는
 *       {@code 200 OK} 인데 본문이 비어서 온다 → 목록(hrefs)을 받은 뒤
 *       {@code calendar-multiget} 으로 본문을 따로 받는 <b>2단계 조회</b>를 한다. 한 번에
 *       끝내려 하면 일정이 0건으로 보인다.</li>
 *   <li>{@code <C:expand>} 를 받고도 무시하는 서버가 있다 → 반복 전개를 서버에 맡기지 않고
 *       {@link RecurrenceExpander} 가 직접 한다.</li>
 * </ol>
 *
 * <p>시각은 UTC 로만 오지 않는다({@code DTSTART;TZID=...}) → {@link ICalDateTime} 참조.
 * 응답에 {@code VTIMEZONE} 이 딸려 오고 그 안에도 {@code DTSTART}·{@code RRULE} 이 있어
 * {@link ICalParser} 가 컴포넌트 경계로 거른다.
 *
 * <p>🔴 <b>실계정 왕복은 아직 검증되지 않았다.</b> 위 동작은 CalDAV 표준과 다른 제공자에서의
 * 실측을 근거로 짠 것이고, iCloud 자체를 상대로는 아직 돌려 보지 못했다.
 * {@code AppleCalDavLiveTest} 에 자격증명을 주고 한 번 돌려 확인할 것.
 */
@Component
public class AppleCalDavProvider implements CalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(AppleCalDavProvider.class);

    private static final String BASE = "https://caldav.icloud.com";
    private static final HttpMethod PROPFIND = HttpMethod.valueOf("PROPFIND");
    private static final HttpMethod REPORT = HttpMethod.valueOf("REPORT");

    /** iCalendar 기본형 UTC — {@code time-range} 필터가 요구하는 표기. */
    private static final DateTimeFormatter ICAL_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));

    /**
     * {@code TZID} 도 {@code Z} 도 없는 시각(floating)을 해석할 지역.
     *
     * <p>iCloud 응답은 계정 지역에 따라 다른 TZID 로 온다. floating 을 UTC 로
     * 읽으면 9시간이 밀리므로, 모르면 UTC 가 아니라 여기로 떨어뜨린다.
     */
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Seoul");

    /** 한 번에 본문을 받아올 일정 수 상한 — 요청 XML 이 지나치게 커지지 않게 나눠 보낸다. */
    private static final int MULTIGET_BATCH = 100;

    private final RestClient restClient;

    public AppleCalDavProvider(RestClient calDavRestClient) {
        this.restClient = calDavRestClient;
    }

    @Override
    public ExternalCalendarProvider provider() {
        return ExternalCalendarProvider.APPLE;
    }

    /**
     * 캘린더 목록 (ONB-08) — principal → calendar-home-set → 모음 순회.
     *
     * <p>경로를 규칙으로 조립하지 않고 <b>서버가 알려주는 대로 따라간다.</b> 실측에서 홈이
     * {@code /caldav/{id}/calendar/} 였지만, 그 모양을 상수로 박으면 애플이 바꾸는 날 전부 404 가 된다.
     *
     * <p><b>{@code VTODO} 모음은 거른다.</b> iCloud 는 "미리 알림"을 캘린더와 같은 자리에 두는데
     * ({@code supported-calendar-component-set} 이 {@code VTODO}), 거르지 않으면 <b>할 일 목록이 캘린더로
     * 보이고</b> 사용자가 그것을 선택한 뒤 일정 0건을 만난다.
     */
    @Override
    public List<ProviderCalendar> listCalendars(ProviderCredential credential) {
        String principal = firstHref(
                dav(credential, "/", 0, PROPFIND, """
                        <?xml version="1.0" encoding="utf-8"?>
                        <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>"""),
                "current-user-principal");
        if (principal == null) {
            throw providerFailure("current-user-principal 없음");
        }

        String home = firstHref(
                dav(credential, principal, 0, PROPFIND, """
                        <?xml version="1.0" encoding="utf-8"?>
                        <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                          <d:prop><c:calendar-home-set/></d:prop></d:propfind>"""),
                "calendar-home-set");
        if (home == null) {
            throw providerFailure("calendar-home-set 없음");
        }

        Element root = dav(credential, home, 1, PROPFIND, """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:resourcetype/><d:displayname/><c:supported-calendar-component-set/></d:prop>
                </d:propfind>""");

        List<ProviderCalendar> calendars = new ArrayList<>();
        for (Element response : children(root, "response")) {
            String href = text(response, "href");
            if (href == null || href.equals(home)) {
                continue;
            }
            if (element(response, "calendar") == null) {
                continue;
            }
            if (!supportsEvents(response)) {
                continue;
            }
            String name = text(response, "displayname");
            calendars.add(new ProviderCalendar(href, name != null && !name.isBlank() ? name : href));
        }
        return calendars;
    }

    /**
     * 기간 내 일정 (ONB-08/09) — 2단계 조회 후 반복 전개.
     *
     * <p><b>{@code externalEventId} 를 합성한다.</b> 반복 일정은 {@code .ics} 파일 하나(UID 하나)가 여러
     * 회차가 되는데, 재동기화는 이 값으로 같은 행을 찾는다. UID 만 쓰면 <b>매주 회의 전체가 한 행으로
     * 뭉개져</b> 회차마다 다른 반영 여부(제외·수정)를 담을 수 없다. 그래서 {@code UID#시작시각} 으로 둔다.
     */
    @Override
    public List<ProviderEvent> listEvents(ProviderCredential credential, String externalCalendarId,
                                          String calendarName, Instant from, Instant to) {
        String query = """
                <?xml version="1.0" encoding="utf-8"?>
                <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><d:getetag/></d:prop>
                  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT">
                    <c:time-range start="%s" end="%s"/>
                  </c:comp-filter></c:comp-filter></c:filter>
                </c:calendar-query>""".formatted(ICAL_UTC.format(from), ICAL_UTC.format(to));

        List<String> hrefs = new ArrayList<>();
        for (Element response : children(dav(credential, externalCalendarId, 1, REPORT, query), "response")) {
            String href = text(response, "href");
            if (href != null && !href.equals(externalCalendarId)) {
                hrefs.add(href);
            }
        }
        if (hrefs.isEmpty()) {
            return List.of();
        }

        List<ProviderEvent> events = new ArrayList<>();
        for (int offset = 0; offset < hrefs.size(); offset += MULTIGET_BATCH) {
            List<String> batch = hrefs.subList(offset, Math.min(offset + MULTIGET_BATCH, hrefs.size()));
            collect(fetchBodies(credential, externalCalendarId, batch), calendarName, from, to, events);
        }
        return events;
    }

    /** {@code calendar-multiget} — 방언 ①: 본문은 여기서만 온다. */
    private List<String> fetchBodies(ProviderCredential credential, String calendarHref, List<String> hrefs) {
        StringBuilder body = new StringBuilder("""
                <?xml version="1.0" encoding="utf-8"?>
                <c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop><c:calendar-data/></d:prop>
                """);
        for (String href : hrefs) {
            body.append("  <d:href>").append(escapeXml(href)).append("</d:href>\n");
        }
        body.append("</c:calendar-multiget>");

        List<String> bodies = new ArrayList<>();
        for (Element response : children(dav(credential, calendarHref, 1, REPORT, body.toString()), "response")) {
            String data = text(response, "calendar-data");
            if (data != null && !data.isBlank()) {
                bodies.add(data);
            }
        }
        return bodies;
    }

    /**
     * 발생의 종료 시각. 정할 수 없으면 {@code null} <b>과 함께 경고를 남긴다</b> — 조용히 사라지지 않게.
     *
     * <p>RFC5545 는 종료를 {@code DTEND} 로도, {@code DURATION} 으로도 적게 허용한다. 옛 구현은
     * {@code DTEND} 만 읽어서, {@code DURATION} 만 쓴 VEVENT 는 길이 0 이 되어 아래 발생 필터에서
     * <b>로그 없이 걸러졌다</b>(2026-08-27 리뷰 지적 — 서드파티 CalDAV 클라이언트에서 드물지 않다).
     */
    private static Instant endOf(ICalParser.Component event, Instant start, String calendarName) {
        Instant end = ICalDateTime.parse(event.first("DTEND"), DEFAULT_ZONE);
        if (end != null && end.isAfter(start)) {
            return end;
        }

        ICalParser.Property duration = event.first("DURATION");
        if (duration != null) {
            Duration length = ICalDateTime.parseDuration(duration.value());
            if (length != null) {
                return start.plus(length);
            }
            log.warn("DURATION 을 해석하지 못해 일정 한 건을 건너뛴다 — calendar={} uid={} value={}",
                    calendarName, event.value("UID"), duration.value());
            return null;
        }

        if (end == null) {
            log.warn("종료 시각이 없어(DTEND·DURATION 둘 다 없음) 일정 한 건을 건너뛴다 — calendar={} uid={}",
                    calendarName, event.value("UID"));
        } else {
            log.warn("종료가 시작보다 앞서거나 같아 일정 한 건을 건너뛴다 — calendar={} uid={} start={} end={}",
                    calendarName, event.value("UID"), start, end);
        }
        return null;
    }

    private void collect(List<String> icsBodies, String calendarName, Instant from, Instant to,
                         List<ProviderEvent> target) {
        for (String ics : icsBodies) {
            for (ICalParser.Component event : ICalParser.parseEvents(ics)) {
                ICalParser.Property dtStart = event.first("DTSTART");
                if (ICalDateTime.isAllDay(dtStart)) {
                    // 종일 일정은 시각이 없어 옮길 자리가 없고, 하루를 통째로 채우면 의도하지 않은 차단이 된다.
                    continue;
                }
                Instant start = ICalDateTime.parse(dtStart, DEFAULT_ZONE);
                if (start == null) {
                    // 🔴 조용히 건너뛰지 않는다 (2026-08-27 리뷰 지적). 예전에는 로그 한 줄 없이
                    //    continue 했고, 그러면 그 일정만 흔적 없이 후보에서 빠진다 —
                    //    이 저장소가 이미 한 번 당한 "파싱 실패 → 일정이 조용히 사라짐" 과 같은 계열이다.
                    //    사용자는 "없는 일정" 으로 보고 그 시간 위에 계획을 얹고, 서버 로그로도 원인을 알 수 없다.
                    log.warn("DTSTART 를 해석하지 못해 일정 한 건을 건너뛴다 — calendar={} uid={} value={}",
                            calendarName, event.value("UID"), dtStart == null ? null : dtStart.value());
                    continue;
                }
                Instant end = endOf(event, start, calendarName);
                if (end == null) {
                    continue;   // 위에서 사유를 로그로 남겼다.
                }
                ZoneId zone = zoneOf(dtStart);

                String uid = event.value("UID");
                String title = event.value("SUMMARY");
                for (RecurrenceExpander.Occurrence occurrence :
                        RecurrenceExpander.expand(event, start, end, zone, from, to)) {
                    if (!occurrence.endAt().isAfter(occurrence.startAt())) {
                        continue;
                    }
                    target.add(new ProviderEvent(
                            occurrenceId(uid, occurrence.startAt()),
                            title != null && !title.isBlank() ? title : "(제목 없음)",
                            occurrence.startAt(), occurrence.endAt(), calendarName));
                }
            }
        }
    }

    private static String occurrenceId(String uid, Instant start) {
        String base = uid != null && !uid.isBlank() ? uid : "unknown";
        return base + "#" + start.getEpochSecond();
    }

    private static ZoneId zoneOf(ICalParser.Property dtStart) {
        String tzid = dtStart == null ? null : dtStart.param("TZID");
        if (tzid == null || tzid.isBlank()) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(tzid.replace("\"", "").trim());
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    /** CalDAV 한 번 왕복 — 실패는 여기서 계약된 오류로 바꾼다. */
    private Element dav(ProviderCredential credential, String path, int depth, HttpMethod method, String body) {
        byte[] xml;
        try {
            // 🔴 uri(String) 이 아니라 uri(URI) 로 넘긴다. 문자열 오버로드는 내부
            //    UriBuilderFactory(TEMPLATE_AND_VALUES)가 값을 **다시** 인코딩하는데,
            //    이미 유효한 %XX 시퀀스까지 재인코딩한다 — already%20encoded 가
            //    already%2520encoded 가 된다(직접 실행 확인). CalDAV 의 href 는 서버가
            //    주는 원문이고 iCloud 는 이미 인코딩된 형태로 줄 수 있으므로, 그대로
            //    문자열로 넘기면 두 번째 왕복이 실제 리소스와 어긋나 404 → 502 가 된다.
            //    GoogleCalendarProvider 가 캘린더 ID 의 '#'(%23 → %2523)에서 겪고
            //    이미 uri(URI) 로 우회한 것과 같은 결함이다.
            xml = restClient.method(method)
                    .uri(URI.create(path.startsWith("http") ? path : BASE + path))
                    .header(HttpHeaders.AUTHORIZATION, basic(credential))
                    .header("Depth", String.valueOf(depth))
                    .contentType(MediaType.APPLICATION_XML)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                // 아이디·앱 비밀번호가 틀렸거나 사용자가 애플에서 앱 암호를 회수했다. 사용자가 고칠 수 있는 오류다.
                log.warn("애플 CalDAV 자격증명 거부: status={}", e.getStatusCode().value());
                throw new OpenPlanException(ErrorCode.E_EXT_002,
                        Map.of("provider", ExternalCalendarProvider.APPLE.name()));
            }
            log.warn("애플 CalDAV 오류: status={} path={}", e.getStatusCode().value(), path);
            throw providerFailure("status=" + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("애플 CalDAV 호출 실패: path={}", path, e);
            throw providerFailure(e.getClass().getSimpleName());
        }
        return parseXml(xml);
    }

    private static String basic(ProviderCredential credential) {
        String raw = credential.accountIdentifier() + ":" + credential.secret();
        return "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 응답 XML 파싱.
     *
     * <p><b>왜 {@code String} 이 아니라 바이트로 받는가.</b> 문자열로 받으면 디코딩을 HTTP 헤더의
     * {@code charset} 에 맡기게 되는데, 그것이 없으면 Spring 은 ISO-8859-1 로 읽는다 — 한글 일정 제목이
     * <b>예외 없이 깨진 글자로</b> 들어온다. XML 은 본문 첫 줄의 {@code encoding} 선언이 권위이므로
     * 바이트를 그대로 넘겨 파서가 판단하게 한다. (실제 iCloud 는 {@code charset=UTF-8} 을 붙이지만,
     * 제공자 헤더에 의존하는 대신 규격이 정한 순서를 따른다.)
     *
     * <p><b>외부 엔티티를 끈다.</b> 제공자 응답도 외부 입력이다 — DTD 를 허용하면 응답 한 줄로 서버의
     * 로컬 파일을 읽게 만드는 경로(XXE)가 열린다.
     */
    private static Element parseXml(byte[] xml) {
        if (xml == null || xml.length == 0) {
            throw providerFailure("빈 응답");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
        } catch (Exception e) {
            throw providerFailure("XML 파싱 실패");
        }
    }

    /** {@code supported-calendar-component-set} 에 {@code VEVENT} 가 있는가 — 없으면 할 일 모음이다. */
    private static boolean supportsEvents(Element response) {
        NodeList comps = response.getElementsByTagNameNS("*", "comp");
        if (comps.getLength() == 0) {
            // 표기가 없으면 일정 모음으로 본다 — 거르는 쪽으로 틀리면 사용자의 캘린더가 사라진다.
            return true;
        }
        for (int i = 0; i < comps.getLength(); i++) {
            if ("VEVENT".equalsIgnoreCase(((Element) comps.item(i)).getAttribute("name"))) {
                return true;
            }
        }
        return false;
    }

    private static List<Element> children(Element root, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) nodes.item(i));
            }
        }
        return out;
    }

    private static Element element(Element scope, String localName) {
        NodeList nodes = scope.getElementsByTagNameNS("*", localName);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Element scope, String localName) {
        Element found = element(scope, localName);
        return found == null ? null : found.getTextContent().trim();
    }

    private static String firstHref(Element root, String parentLocalName) {
        Element parent = element(root, parentLocalName);
        return parent == null ? null : text(parent, "href");
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static OpenPlanException providerFailure(String detail) {
        log.warn("애플 CalDAV 실패: {}", detail);
        return new OpenPlanException(ErrorCode.E_EXT_001,
                Map.of("provider", ExternalCalendarProvider.APPLE.name()));
    }
}
