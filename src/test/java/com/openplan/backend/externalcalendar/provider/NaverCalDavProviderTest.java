package com.openplan.backend.externalcalendar.provider;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 네이버 CalDAV 어댑터 (ST-B1-11). 제공자 응답만 대역 — 요청 조립·XML 파싱·반복 전개는 실제로 돈다.
 *
 * <p><b>응답 픽스처는 2026-08-20 실계정 응답의 구조</b>다(값만 익명화). 네임스페이스 접두·CDATA·
 * 빈 {@code <D:prop/>} 같은 것이 실물과 같아야 방언을 잡는 의미가 있다.
 *
 * <p><b>이 테스트가 덮지 못하는 것</b>: {@link MockRestServiceServer} 는 HTTP 스택을 통째로 대체하므로
 * {@code PROPFIND}·{@code REPORT} 가 <b>실제 전선으로 나가는지</b>는 여기서 증명되지 않는다.
 * 그것은 {@code CalDavHttpMethodTest} 가 로컬 실서버로 확인한다.
 */
class NaverCalDavProviderTest {

    private static final ProviderCredential CREDENTIAL = ProviderCredential.basic("tester", "app-password");
    private static final String BASE = "https://caldav.calendar.naver.com";
    private static final String HOME = "/caldav/tester/calendar/";
    private static final String CAL = HOME + "e8faeab7-cccf-45e2-8b69-e813570d829c/";
    private static final String EVENT_HREF = CAL + "AAAA1111_tester%40naver.com_caldavApp.ics";

    private MockRestServiceServer server;
    private NaverCalDavProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new NaverCalDavProvider(builder.build());
    }

    @Test
    @DisplayName("경로를 규칙으로 조립하지 않고 principal → home 순으로 따라간다")
    void 서버가_알려준_경로를_따라간다() {
        expectPropfind(BASE + "/", principalResponse());
        expectPropfind(BASE + "/principals/users/tester/", homeResponse());
        expectPropfind(BASE + HOME, calendarsResponse());

        List<ProviderCalendar> calendars = provider.listCalendars(CREDENTIAL);

        server.verify();
        assertThat(calendars).extracting(ProviderCalendar::externalCalendarId).containsExactly(CAL);
    }

    @Test
    @DisplayName("VTODO 모음(내 할 일)은 캘린더 목록에서 거른다")
    void 할일_모음은_캘린더가_아니다() {
        expectPropfind(BASE + "/", principalResponse());
        expectPropfind(BASE + "/principals/users/tester/", homeResponse());
        expectPropfind(BASE + HOME, calendarsResponse());

        List<ProviderCalendar> calendars = provider.listCalendars(CREDENTIAL);

        // 픽스처에는 VEVENT 1개 · VTODO 1개가 들어 있다. 거르지 않으면 할 일이 캘린더로 보이고
        // 사용자가 그것을 고른 뒤 일정 0건을 만난다.
        assertThat(calendars).hasSize(1);
        assertThat(calendars.getFirst().name()).isEqualTo("내 캘린더");
    }

    @Test
    @DisplayName("Basic 자격증명을 싣는다 — 아이디와 비밀번호가 함께 있어야 한 번의 호출이 선다")
    void Basic_헤더를_조립한다() {
        // "tester:app-password" 의 Base64
        String expected = "Basic dGVzdGVyOmFwcC1wYXNzd29yZA==";
        server.expect(once(), requestTo(BASE + "/"))
                .andExpect(header("Authorization", expected))
                .andRespond(withSuccess(principalResponse(), MediaType.APPLICATION_XML));
        expectPropfind(BASE + "/principals/users/tester/", homeResponse());
        expectPropfind(BASE + HOME, calendarsResponse());

        provider.listCalendars(CREDENTIAL);

        server.verify();
    }

    @Test
    @DisplayName("일정 조회는 2단계다 — calendar-query 가 본문을 안 주므로 multiget 으로 다시 받는다")
    void 목록과_본문을_따로_받는다() {
        server.expect(once(), requestTo(BASE + CAL))
                .andExpect(method(HttpMethod.valueOf("REPORT")))
                .andExpect(header("Depth", "1"))
                .andRespond(withSuccess(queryResponseWithoutData(), MediaType.APPLICATION_XML));
        server.expect(once(), requestTo(BASE + CAL))
                .andExpect(method(HttpMethod.valueOf("REPORT")))
                .andRespond(withSuccess(multigetResponse(), MediaType.APPLICATION_XML));

        List<ProviderEvent> events = provider.listEvents(CREDENTIAL, CAL, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        server.verify();
        assertThat(events).hasSize(1);
        ProviderEvent event = events.getFirst();
        assertThat(event.title()).isEqualTo("팀 점검");
        // DTSTART;TZID=Asia/Seoul:20260820T100000 → 01:00Z. UTC 로 읽었으면 10:00Z 가 됐을 것이다.
        assertThat(event.startAt()).isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
        assertThat(event.sourceCalendar()).isEqualTo("내 캘린더");
    }

    @Test
    @DisplayName("목록이 비면 본문 요청을 아예 보내지 않는다")
    void 빈_목록에는_추가_요청이_없다() {
        server.expect(once(), requestTo(BASE + CAL))
                .andRespond(withSuccess(emptyMultistatus(), MediaType.APPLICATION_XML));

        List<ProviderEvent> events = provider.listEvents(CREDENTIAL, CAL, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        server.verify();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("반복 일정은 회차마다 서로 다른 externalEventId 를 받는다")
    void 회차별로_식별자가_갈린다() {
        server.expect(once(), requestTo(BASE + CAL))
                .andRespond(withSuccess(queryResponseWithoutData(), MediaType.APPLICATION_XML));
        server.expect(once(), requestTo(BASE + CAL))
                .andRespond(withSuccess(multigetRecurringResponse(), MediaType.APPLICATION_XML));

        List<ProviderEvent> events = provider.listEvents(CREDENTIAL, CAL, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        // 6월에 시작한 매주 화요일 → 8/18 · 8/25. UID 만 썼으면 한 행으로 뭉개져
        // 회차별 반영 여부(제외·수정)를 담을 수 없다.
        assertThat(events).hasSize(2);
        assertThat(events).extracting(ProviderEvent::externalEventId).doesNotHaveDuplicates();
        assertThat(events).extracting(ProviderEvent::startAt).containsExactly(
                Instant.parse("2026-08-18T01:00:00Z"), Instant.parse("2026-08-25T01:00:00Z"));
    }

    @Test
    @DisplayName("401 은 502 가 아니라 422 다 — 사용자가 고칠 수 있는 오류이기 때문")
    void 자격증명_거부는_422() {
        server.expect(once(), requestTo(BASE + "/"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> provider.listCalendars(CREDENTIAL))
                .isInstanceOf(OpenPlanException.class)
                .extracting(e -> ((OpenPlanException) e).errorCode())
                .isEqualTo(ErrorCode.E_EXT_002);
    }

    @Test
    @DisplayName("제공자 장애는 502 로 남는다 — 자격증명 오류와 화면이 달라야 한다")
    void 제공자_장애는_502() {
        server.expect(once(), requestTo(BASE + "/"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> provider.listCalendars(CREDENTIAL))
                .isInstanceOf(OpenPlanException.class)
                .extracting(e -> ((OpenPlanException) e).errorCode())
                .isEqualTo(ErrorCode.E_EXT_001);
    }

    private void expectPropfind(String uri, String body) {
        server.expect(once(), requestTo(uri))
                .andExpect(method(HttpMethod.valueOf("PROPFIND")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_XML));
    }

    // ─────────────────────────── 픽스처 (실측 구조 · 값만 익명화)

    private static final String NS = """
            xmlns:D="DAV:" xmlns:caldav="urn:ietf:params:xml:ns:caldav" \
            xmlns:cs="http://calendarserver.org/ns/" xmlns:navercal="http://calendar.naver.com/\"""";

    private static String principalResponse() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus %s>
                  <D:response><D:href>/</D:href><D:propstat><D:prop>
                    <D:current-user-principal><D:href>/principals/users/tester/</D:href></D:current-user-principal>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                </D:multistatus>""".formatted(NS);
    }

    private static String homeResponse() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus %s>
                  <D:response><D:href>/principals/users/tester/</D:href><D:propstat><D:prop>
                    <caldav:calendar-home-set><D:href>%s</D:href></caldav:calendar-home-set>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                </D:multistatus>""".formatted(NS, HOME);
    }

    /** 실측과 같은 모양 — 모음 자신 + VEVENT 캘린더 + VTODO("내 할 일"). displayname 은 CDATA 로 온다. */
    private static String calendarsResponse() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus %s>
                  <D:response><D:href>%s</D:href><D:propstat><D:prop>
                    <D:resourcetype><D:collection/></D:resourcetype>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                  <D:response><D:href>%s</D:href><D:propstat><D:prop>
                    <D:resourcetype><D:collection/><caldav:calendar/></D:resourcetype>
                    <D:displayname><![CDATA[내 캘린더]]></D:displayname>
                    <caldav:supported-calendar-component-set><caldav:comp name="VEVENT"/></caldav:supported-calendar-component-set>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                  <D:response><D:href>%sd7368719-4592-4eb1-86db-0462bb911e2c/</D:href><D:propstat><D:prop>
                    <D:resourcetype><D:collection/><caldav:calendar/></D:resourcetype>
                    <D:displayname><![CDATA[내 할 일]]></D:displayname>
                    <caldav:supported-calendar-component-set><caldav:comp name="VTODO"/></caldav:supported-calendar-component-set>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                </D:multistatus>""".formatted(NS, HOME, CAL, HOME);
    }

    /** 🔴 방언 — 상태는 200 인데 calendar-data 가 없다. etag 만 온다. */
    private static String queryResponseWithoutData() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus %s>
                  <D:response><D:href>%s</D:href><D:propstat><D:prop>
                    <D:getetag>"2026-08-14 14:18:47"</D:getetag>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                </D:multistatus>""".formatted(NS, EVENT_HREF);
    }

    private static String emptyMultistatus() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<D:multistatus %s></D:multistatus>".formatted(NS);
    }

    private static String multigetResponse() {
        return multiget("""
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VTIMEZONE
                TZID:Asia/Seoul
                BEGIN:DAYLIGHT
                DTSTART:19870510T000000
                RRULE:FREQ=YEARLY;UNTIL=19880507T170000Z;BYMONTH=5;BYDAY=2SU
                END:DAYLIGHT
                END:VTIMEZONE
                BEGIN:VEVENT
                UID:AAAA1111_tester@naver.com_caldavApp
                SUMMARY:팀 점검
                DTSTART;TZID=Asia/Seoul:20260820T100000
                DTEND;TZID=Asia/Seoul:20260820T110000
                END:VEVENT
                END:VCALENDAR""");
    }

    private static String multigetRecurringResponse() {
        return multiget("""
                BEGIN:VCALENDAR
                VERSION:2.0
                BEGIN:VEVENT
                UID:weekly-tester@naver.com
                SUMMARY:주간 회의
                DTSTART:20260602T010000Z
                DTEND:20260602T020000Z
                RRULE:FREQ=WEEKLY;BYDAY=TU
                END:VEVENT
                END:VCALENDAR""");
    }

    private static String multiget(String ics) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <D:multistatus %s>
                  <D:response><D:href>%s</D:href><D:propstat><D:prop>
                    <caldav:calendar-data>%s</caldav:calendar-data>
                  </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>
                </D:multistatus>""".formatted(NS, EVENT_HREF, ics);
    }
}
