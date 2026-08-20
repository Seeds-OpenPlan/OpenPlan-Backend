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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * CalDAV 캘린더 어댑터 공통 기반 (RFC 4791) — ST-B1-11.
 *
 * <p><b>왜 공통 기반인가.</b> 캘린더 조회를 여는 방식은 둘로 갈린다. 구글은 REST + OAuth 이지만,
 * <b>애플은 서드파티용 캘린더 REST API 를 제공하지 않는다</b> — 열려 있는 것은 CalDAV 뿐이고
 * 인증은 Apple ID + <b>앱 전용 비밀번호</b>의 HTTP Basic 이다
 * ({@code caldav.icloud.com} → {@code WWW-Authenticate: Basic realm="MMCalDav"}, 2026-08-21 실측).
 * 네이버도 같은 모양이었다. 제공자가 달라도 CalDAV 절차는 표준이므로 <b>흐름을 여기 두고
 * 호스트·방언만 하위에서 정한다.</b>
 *
 * <p><b>조회는 2단계다.</b> 목록({@code calendar-query})으로 {@code href} 를 받고 본문
 * ({@code calendar-multiget})을 따로 받는다. 표준상 {@code calendar-query} 가 {@code calendar-data} 를
 * 함께 줄 수 있지만 <b>그러지 않는 서버가 실재한다</b> — 네이버는 상태 {@code 200 OK} 에
 * {@code <D:prop/>} 이 빈 채로 왔다(2026-08-20 실측). 한 번에 끝내려 하면 그런 서버에서 일정이
 * <b>오류 없이 0건</b>으로 보인다. 2단계는 모든 서버에서 옳고, 비용은 왕복 한 번이다.
 *
 * <p><b>{@code <C:expand>} 를 요청하지 않는다.</b> 서버가 펼쳐 주면 편하지만 <b>받고도 무시하는
 * 서버가 실재하고</b>(네이버 실측 — 붙이든 안 붙이든 응답이 같았다) 그 무시는 오류로 드러나지 않는다.
 * 대신 {@link RecurrenceExpander} 가 항상 클라이언트에서 펼친다. 서버가 이미 펼쳐 보냈다면
 * {@code RRULE} 이 없으므로 전개기가 그대로 통과시킨다 — <b>어느 쪽이든 옳다.</b>
 *
 * <p><b>시각은 {@code TZID} 를 존중한다</b>(→ {@link ICalDateTime}). {@code VTIMEZONE} 안에도
 * {@code DTSTART}·{@code RRULE} 이 있으므로 컴포넌트 경계로 걸러야 한다(→ {@link ICalParser}).
 */
public abstract class CalDavCalendarProvider implements CalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(CalDavCalendarProvider.class);

    private static final HttpMethod PROPFIND = HttpMethod.valueOf("PROPFIND");
    private static final HttpMethod REPORT = HttpMethod.valueOf("REPORT");

    /** iCalendar 기본형 UTC — {@code time-range} 필터가 요구하는 표기. */
    private static final DateTimeFormatter ICAL_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"));

    /** 제공자 CalDAV 진입 호스트. 경로는 서버가 알려주는 대로 따라가므로 여기만 다르면 된다. */
    protected abstract String baseUrl();

    /**
     * {@code TZID} 도 {@code Z} 도 없는 시각(floating)을 해석할 지역.
     *
     * <p>floating 은 "보는 사람의 현지 시각"이라는 뜻이라 서버가 임의로 UTC 로 읽으면 안 된다 —
     * 그렇게 읽으면 한국 사용자의 일정이 9시간 밀린다. 사용자층이 제공자마다 다르므로 하위에서 정한다.
     */
    protected abstract ZoneId defaultZone();

    /** 한 번에 본문을 받아올 일정 수 상한 — 요청 XML 이 지나치게 커지지 않게 나눠 보낸다. */
    private static final int MULTIGET_BATCH = 100;

    private final RestClient restClient;

    protected CalDavCalendarProvider(RestClient calDavRestClient) {
        this.restClient = calDavRestClient;
    }

    /**
     * 캘린더 목록 (ONB-08) — principal → calendar-home-set → 모음 순회.
     *
     * <p>경로를 규칙으로 조립하지 않고 <b>서버가 알려주는 대로 따라간다.</b> 홈 경로 모양은 제공자마다 다르고
     * 예고 없이 바뀔 수 있다 — 상수로 박으면 그날 전부 404 가 된다.
     *
     * <p><b>{@code VTODO} 모음은 거른다.</b> 제공자들은 "할 일" 모음을 캘린더와 같은 자리에 두는데
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

    private void collect(List<String> icsBodies, String calendarName, Instant from, Instant to,
                         List<ProviderEvent> target) {
        for (String ics : icsBodies) {
            for (ICalParser.Component event : ICalParser.parseEvents(ics)) {
                ICalParser.Property dtStart = event.first("DTSTART");
                if (ICalDateTime.isAllDay(dtStart)) {
                    // 종일 일정은 시각이 없어 옮길 자리가 없고, 하루를 통째로 채우면 의도하지 않은 차단이 된다.
                    continue;
                }
                Instant start = ICalDateTime.parse(dtStart, defaultZone());
                if (start == null) {
                    continue;
                }
                Instant end = ICalDateTime.parse(event.first("DTEND"), defaultZone());
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

    private ZoneId zoneOf(ICalParser.Property dtStart) {
        String tzid = dtStart == null ? null : dtStart.param("TZID");
        if (tzid == null || tzid.isBlank()) {
            return defaultZone();
        }
        try {
            return ZoneId.of(tzid.replace("\"", "").trim());
        } catch (Exception e) {
            return defaultZone();
        }
    }

    /** CalDAV 한 번 왕복 — 실패는 여기서 계약된 오류로 바꾼다. */
    private Element dav(ProviderCredential credential, String path, int depth, HttpMethod method, String body) {
        byte[] xml;
        try {
            xml = restClient.method(method)
                    .uri(path.startsWith("http") ? path : baseUrl() + path)
                    .header(HttpHeaders.AUTHORIZATION, basic(credential))
                    .header("Depth", String.valueOf(depth))
                    .contentType(MediaType.APPLICATION_XML)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                // 아이디·앱 비밀번호가 틀렸거나 사용자가 네이버에서 회수했다. 사용자가 고칠 수 있는 오류다.
                log.warn("CalDAV 자격증명 거부: provider={} status={}", provider(), e.getStatusCode().value());
                throw new OpenPlanException(ErrorCode.E_EXT_002, Map.of("provider", provider().name()));
            }
            log.warn("CalDAV 오류: provider={} status={} path={}", provider(), e.getStatusCode().value(), path);
            throw providerFailure("status=" + e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("CalDAV 호출 실패: provider={} path={}", provider(), path, e);
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
     * 바이트를 그대로 넘겨 파서가 판단하게 한다. (실제 네이버는 {@code charset=UTF-8} 을 붙이지만,
     * 제공자 헤더에 의존하는 대신 규격이 정한 순서를 따른다.)
     *
     * <p><b>외부 엔티티를 끈다.</b> 제공자 응답도 외부 입력이다 — DTD 를 허용하면 응답 한 줄로 서버의
     * 로컬 파일을 읽게 만드는 경로(XXE)가 열린다.
     */
    private Element parseXml(byte[] xml) {
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

    private OpenPlanException providerFailure(String detail) {
        log.warn("CalDAV 실패: provider={} {}", provider(), detail);
        return new OpenPlanException(ErrorCode.E_EXT_001, Map.of("provider", provider().name()));
    }
}
