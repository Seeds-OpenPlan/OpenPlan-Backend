package com.openplan.backend.externalcalendar.provider;

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
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/**
 * 카카오 톡캘린더 어댑터 (ST-B1-11). 제공자 응답만 대역 — 파싱·구간 분할은 실제로 돈다.
 *
 * <p>검증의 축은 셋이다: ⑴ 31일 상한을 <b>어댑터가 흡수</b>하는가(상위 계층은 63일을 그대로 요청한다)
 * ⑵ 조각 경계에 걸친 일정이 <b>두 번 세어지지 않는가</b> ⑶ 옮길 수 없는 일정(종일)을 거르는가.
 */
class KakaoCalendarProviderTest {

    /** 카카오는 Bearer 만 쓴다 — 자격증명 조립이 아니라 응답 파싱이 이 테스트의 대상이다. */
    private static final ProviderCredential CREDENTIAL = ProviderCredential.bearer("token");

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private KakaoCalendarProvider provider;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new KakaoCalendarProvider(builder.build());
    }

    @Test
    @DisplayName("63일 구간은 세 번에 나눠 부른다 — 카카오는 to 가 from 기준 31일 이내여야 한다")
    void splitsRangeIntoWindows() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = from.plusSeconds(63L * 24 * 3600);

        server.expect(once(), requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/events")))
                .andRespond(withSuccess(emptyEvents(), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/events")))
                .andRespond(withSuccess(emptyEvents(), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/events")))
                .andRespond(withSuccess(emptyEvents(), MediaType.APPLICATION_JSON));

        provider.listEvents(CREDENTIAL, "cal-1", "내 캘린더", from, to);

        server.verify();   // 정확히 3회 — 한 번이라도 31일을 넘겼으면 카카오가 거절한다
    }

    @Test
    @DisplayName("조각 경계에 걸친 같은 일정은 한 번만 센다")
    void deduplicatesAcrossWindows() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = from.plusSeconds(45L * 24 * 3600);   // 30 + 15 → 두 조각

        String sameEvent = """
                {"events":[{"id":"ev-1","title":"회의","time":
                  {"start_at":"2026-08-31T01:00:00Z","end_at":"2026-08-31T02:00:00Z","all_day":false}}]}""";
        server.expect(once(), requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/events")))
                .andRespond(withSuccess(sameEvent, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/events")))
                .andRespond(withSuccess(sameEvent, MediaType.APPLICATION_JSON));

        List<ProviderEvent> events = provider.listEvents(CREDENTIAL, "cal-1", "내 캘린더", from, to);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).externalEventId()).isEqualTo("ev-1");
    }

    @Test
    @DisplayName("종일 일정은 거른다 — 시각이 없어 고정 일정으로 옮길 자리가 없다")
    void skipsAllDayEvents() {
        server.expect(requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/events")))
                .andRespond(withSuccess("""
                        {"events":[
                          {"id":"all-day","title":"휴가","time":
                            {"start_at":"2026-08-20T00:00:00Z","end_at":"2026-08-21T00:00:00Z","all_day":true}},
                          {"id":"timed","title":"회의","time":
                            {"start_at":"2026-08-20T01:00:00Z","end_at":"2026-08-20T02:00:00Z","all_day":false}}]}""",
                        MediaType.APPLICATION_JSON));

        List<ProviderEvent> events = provider.listEvents(CREDENTIAL, "cal-1", "내 캘린더",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-21T00:00:00Z"));

        assertThat(events).extracting(ProviderEvent::externalEventId).containsExactly("timed");
    }

    @Test
    @DisplayName("RFC5545 기본형 시각도 해석한다 — 확장형만 가정하지 않는다")
    void parsesBasicFormatDateTime() {
        server.expect(requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/events")))
                .andRespond(withSuccess("""
                        {"events":[{"id":"ev-1","title":"회의","time":
                          {"start_at":"20260820T010000Z","end_at":"20260820T020000Z","all_day":false}}]}""",
                        MediaType.APPLICATION_JSON));

        List<ProviderEvent> events = provider.listEvents(CREDENTIAL, "cal-1", "내 캘린더",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-21T00:00:00Z"));

        assertThat(events).hasSize(1);
        assertThat(events.get(0).startAt()).isEqualTo(Instant.parse("2026-08-20T01:00:00Z"));
        assertThat(events.get(0).endAt()).isEqualTo(Instant.parse("2026-08-20T02:00:00Z"));
    }

    @Test
    @DisplayName("캘린더 목록에 구독 캘린더도 포함한다 — 무엇을 가져올지는 사용자가 고른다")
    void includesSubscribedCalendars() {
        server.expect(requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/calendars")))
                .andExpect(queryParam("filter", "ALL"))
                .andExpect(header("Authorization", "Bearer token"))
                .andRespond(withSuccess("""
                        {"calendars":[{"id":"primary","name":"내 캘린더"}],
                         "subscribe_calendars":[{"id":"holiday","name":"대한민국 공휴일"}]}""",
                        MediaType.APPLICATION_JSON));

        List<ProviderCalendar> calendars = provider.listCalendars(CREDENTIAL);

        assertThat(calendars).extracting(ProviderCalendar::externalCalendarId)
                .containsExactly("primary", "holiday");
    }

    @Test
    @DisplayName("제공자 오류는 502 E-EXT-001 로 올린다 — 재시도하지 않는다")
    void providerFailureBecomesBadGateway() {
        server.expect(once(), requestTo(startsWith("https://kapi.kakao.com/v2/api/calendar/calendars")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.listCalendars(CREDENTIAL))
                .isInstanceOf(OpenPlanException.class);

        server.verify();   // 정확히 1회 — 재시도가 있었다면 초과 호출로 실패한다
    }

    private static String emptyEvents() {
        return "{\"events\":[]}";
    }
}
