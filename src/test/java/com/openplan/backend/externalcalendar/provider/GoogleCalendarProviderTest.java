package com.openplan.backend.externalcalendar.provider;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 구글 캘린더 어댑터 (ST-B1-11). 제공자 응답만 대역 — 파싱·거르기는 실제로 돈다.
 *
 * <p><b>왜 뒤늦게 붙는가.</b> 이 어댑터는 08-19 에 작성됐지만 컨트롤러 테스트에서 {@code @MockitoBean}
 * 으로 대체돼 있어 <b>어댑터 코드 자체가 한 번도 실행된 적이 없었다</b>(2026-08-20 확인). 카카오에서
 * 시각 파싱이 깨져 <b>일정이 조용히 전부 사라지던</b> 결함을 잡은 것이 정확히 이런 단위 테스트였는데,
 * 구글에는 그 그물이 없었다.
 *
 * <p>검증의 축은 넷이다: ⑴ 상위 계층이 요구한 구간·정렬·<b>반복 전개</b>를 요청에 싣는가
 * ⑵ 옮길 수 없는 일정(종일·시각 역전)을 거르는가 ⑶ 오프셋 시각을 올바로 읽는가
 * ⑷ 조회 상한에 닿은 것을 알아채는가.
 */
class GoogleCalendarProviderTest {

    private static final ProviderCredential CREDENTIAL = ProviderCredential.bearer("google-token");

    /** 구글의 실제 캘린더 ID 는 이메일 모양이다 — 경로에 그대로 들어간다. */
    private static final String CALENDAR_ID = "abc123@group.calendar.google.com";

    private MockRestServiceServer server;
    private GoogleCalendarProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new GoogleCalendarProvider(builder.build());
    }

    @Test
    @DisplayName("반복 일정을 서버가 펼쳐 주도록 singleEvents=true 를 싣는다")
    void 반복_전개를_요청한다() {
        server.expect(once(), requestTo(startsWith("https://www.googleapis.com/calendar/v3/calendars/")))
                .andExpect(queryParam("singleEvents", "true"))
                .andExpect(queryParam("orderBy", "startTime"))
                .andExpect(header("Authorization", "Bearer google-token"))
                .andRespond(withSuccess(events(), MediaType.APPLICATION_JSON));

        provider.listEvents(CREDENTIAL, CALENDAR_ID, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        // 네이버와 달리 구글은 서버가 펼쳐 준다 — 이 파라미터가 빠지면 매주 회의가
        // 규칙 한 건으로 와서 그 주에 안 보인다.
        server.verify();
    }

    @Test
    @DisplayName("조회 구간을 timeMin·timeMax 로 싣는다")
    void 조회_구간을_싣는다() {
        server.expect(once(), requestTo(startsWith("https://www.googleapis.com/calendar/v3/calendars/")))
                .andExpect(queryParam("timeMin", "2026-08-17T00:00:00Z"))
                .andExpect(queryParam("timeMax", "2026-08-31T00:00:00Z"))
                .andRespond(withSuccess(events(), MediaType.APPLICATION_JSON));

        provider.listEvents(CREDENTIAL, CALENDAR_ID, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        server.verify();
    }

    @Test
    @DisplayName("오프셋 시각을 올바로 읽는다 — +09:00 을 UTC 로 환산")
    void 오프셋_시각을_환산한다() {
        server.expect(once(), requestTo(startsWith("https://www.googleapis.com/calendar/v3/calendars/")))
                .andRespond(withSuccess(events(), MediaType.APPLICATION_JSON));

        List<ProviderEvent> result = provider.listEvents(CREDENTIAL, CALENDAR_ID, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        assertThat(result).hasSize(1);
        ProviderEvent event = result.getFirst();
        assertThat(event.title()).isEqualTo("팀 점검");
        // 2026-08-20T10:00:00+09:00 → 01:00Z
        assertThat(event.startAt()).isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
        assertThat(event.endAt()).isEqualTo(Instant.parse("2026-08-20T02:00:00Z"));
        assertThat(event.sourceCalendar()).isEqualTo("내 캘린더");
    }

    @Test
    @DisplayName("종일 일정(start.date)은 후보에서 뺀다 — 하루를 통째로 막으면 의도하지 않은 차단이 된다")
    void 종일은_거른다() {
        String body = """
                {"items":[
                  {"id":"allday-1","summary":"휴가","start":{"date":"2026-08-20"},"end":{"date":"2026-08-21"}}
                ]}""";
        server.expect(once(), requestTo(startsWith("https://www.googleapis.com/calendar/v3/calendars/")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(provider.listEvents(CREDENTIAL, CALENDAR_ID, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("시각이 역전되거나 id 가 없는 항목은 버린다")
    void 망가진_항목을_버린다() {
        String body = """
                {"items":[
                  {"id":"rev-1","summary":"역전","start":{"dateTime":"2026-08-20T11:00:00+09:00"},
                   "end":{"dateTime":"2026-08-20T10:00:00+09:00"}},
                  {"summary":"아이디 없음","start":{"dateTime":"2026-08-20T10:00:00+09:00"},
                   "end":{"dateTime":"2026-08-20T11:00:00+09:00"}}
                ]}""";
        server.expect(once(), requestTo(startsWith("https://www.googleapis.com/calendar/v3/calendars/")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThat(provider.listEvents(CREDENTIAL, CALENDAR_ID, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"))).isEmpty();
    }

    @Test
    @DisplayName("제목이 없는 일정도 후보가 된다 — 시간은 실제로 막혀 있기 때문")
    void 제목_없음도_후보다() {
        String body = """
                {"items":[
                  {"id":"no-title","start":{"dateTime":"2026-08-20T10:00:00+09:00"},
                   "end":{"dateTime":"2026-08-20T11:00:00+09:00"}}
                ]}""";
        server.expect(once(), requestTo(startsWith("https://www.googleapis.com/calendar/v3/calendars/")))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<ProviderEvent> result = provider.listEvents(CREDENTIAL, CALENDAR_ID, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().title()).isEqualTo("(제목 없음)");
    }

    @Test
    @DisplayName("캘린더 목록 — id 와 summary 를 읽는다")
    void 캘린더_목록을_읽는다() {
        String body = """
                {"items":[
                  {"id":"%s","summary":"팀 캘린더"},
                  {"summary":"아이디 없는 것은 버린다"}
                ]}""".formatted(CALENDAR_ID);
        server.expect(once(), requestTo("https://www.googleapis.com/calendar/v3/users/me/calendarList"))
                .andExpect(header("Authorization", "Bearer google-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<ProviderCalendar> calendars = provider.listCalendars(CREDENTIAL);

        assertThat(calendars).hasSize(1);
        assertThat(calendars.getFirst().externalCalendarId()).isEqualTo(CALENDAR_ID);
        assertThat(calendars.getFirst().name()).isEqualTo("팀 캘린더");
    }

    @Test
    @DisplayName("제공자 오류는 502 E-EXT-001 로 올린다 — 재시도 없이")
    void 제공자_오류는_502() {
        server.expect(once(), requestTo("https://www.googleapis.com/calendar/v3/users/me/calendarList"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.listCalendars(CREDENTIAL))
                .isInstanceOf(OpenPlanException.class)
                .extracting(e -> ((OpenPlanException) e).errorCode())
                .isEqualTo(ErrorCode.E_EXT_001);
    }

    @Test
    @DisplayName("조회 상한만큼 와도 우리 쪽에서 잘라내지 않는다 — 넘친 일정은 다음에도 안 온다")
    void 상한까지_전부_읽는다() {
        StringBuilder items = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 250; i++) {
            if (i > 0) {
                items.append(',');
            }
            items.append("""
                    {"id":"evt-%d","summary":"일정","start":{"dateTime":"2026-08-20T10:00:00+09:00"},
                     "end":{"dateTime":"2026-08-20T11:00:00+09:00"}}""".formatted(i));
        }
        items.append("]}");
        server.expect(once(), requestTo(startsWith("https://www.googleapis.com/calendar/v3/calendars/")))
                .andRespond(withSuccess(items.toString(), MediaType.APPLICATION_JSON));

        List<ProviderEvent> result = provider.listEvents(CREDENTIAL, CALENDAR_ID, "내 캘린더",
                Instant.parse("2026-08-17T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));

        // 페이지네이션이 없어 251번째부터는 영영 안 온다(orderBy=startTime 이라 다시 불러도 같은 앞부분).
        // 여기서 우리가 추가로 잘라내기까지 하면 손실이 두 겹이 된다.
        assertThat(result).hasSize(250);
        assertThat(result).extracting(ProviderEvent::externalEventId).doesNotHaveDuplicates();
    }

    /** 구글이 실제로 주는 모양 — 시각은 오프셋 표기다(네이버의 TZID 방식과 다르다). */
    private static String events() {
        return """
                {"items":[
                  {"id":"evt-1","summary":"팀 점검",
                   "start":{"dateTime":"2026-08-20T10:00:00+09:00","timeZone":"Asia/Seoul"},
                   "end":{"dateTime":"2026-08-20T11:00:00+09:00","timeZone":"Asia/Seoul"}}
                ]}""";
    }
}
