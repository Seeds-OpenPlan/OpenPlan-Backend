package com.openplan.backend.externalcalendar.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 구글 캘린더 조회 (ST-B1-11 · ONB-08/09).
 *
 * <p>엔드포인트 둘만 쓴다 — {@code calendarList}(목록)와 {@code events}(일정). 둘 다 읽기 전용이며
 * 최소 권한 scope 는 {@code calendar.readonly} 다.
 *
 * <p><b>타임아웃을 여기서 못박는다</b>(AC1): 연결 3초 · 응답 10초 · <b>서버 자동 재시도 없음</b>.
 * 재시도를 넣지 않는 이유는 사용자가 화면 앞에서 기다리는 동기 경로이기 때문이다 — 재시도는
 * 실패를 늦게 알려줄 뿐이고, 그 사이 요청 스레드가 묶인다.
 *
 * <p>🔴 <b>종일 일정은 거른다.</b> 구글은 종일 일정을 {@code start.date}(시각 없는 날짜)로 주는데,
 * 고정 일정은 요일 + 시분으로 이뤄져 옮길 자리가 없다. 임의로 00:00~23:55 로 채우면 그 날짜의
 * 계획이 통째로 막혀 <b>사용자가 의도하지 않은 차단</b>이 된다. 조용히 왜곡하느니 후보에서 뺀다.
 */
@Component
public class GoogleCalendarProvider implements CalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarProvider.class);

    private static final String CALENDAR_LIST_URI = "https://www.googleapis.com/calendar/v3/users/me/calendarList";
    private static final String EVENTS_URI = "https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events";

    /** 한 번에 받아올 상한. 초과분은 다음 동기화에서 따라온다 — 페이지네이션은 후속. */
    private static final int MAX_RESULTS = 250;

    private final RestClient restClient;

    public GoogleCalendarProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public ExternalCalendarProvider provider() {
        return ExternalCalendarProvider.GOOGLE;
    }

    @Override
    public List<ProviderCalendar> listCalendars(String accessToken) {
        JsonNode body = get(CALENDAR_LIST_URI, accessToken, Map.of());

        List<ProviderCalendar> calendars = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            String id = text(item, "id");
            if (id == null) {
                continue;
            }
            String name = text(item, "summary");
            calendars.add(new ProviderCalendar(id, name != null ? name : id));
        }
        return calendars;
    }

    @Override
    public List<ProviderEvent> listEvents(String accessToken, String externalCalendarId, String calendarName,
                                          Instant from, Instant to) {
        String uri = UriComponentsBuilder.fromUriString(EVENTS_URI)
                .queryParam("timeMin", DateTimeFormatter.ISO_INSTANT.format(from))
                .queryParam("timeMax", DateTimeFormatter.ISO_INSTANT.format(to))
                // 반복 일정을 규칙이 아니라 개별 발생으로 펼쳐 받는다 — 고정 일정은 발생 단위로 만든다.
                .queryParam("singleEvents", "true")
                .queryParam("orderBy", "startTime")
                .queryParam("maxResults", MAX_RESULTS)
                .buildAndExpand(externalCalendarId)
                .toUriString();

        JsonNode body = get(uri, accessToken, Map.of());

        List<ProviderEvent> events = new ArrayList<>();
        for (JsonNode item : body.path("items")) {
            String id = text(item, "id");
            Instant start = dateTime(item.path("start"));
            Instant end = dateTime(item.path("end"));

            // 종일 일정(start.date) · 취소된 발생 · 시각 역전은 후보로 만들지 않는다.
            if (id == null || start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            String title = text(item, "summary");
            events.add(new ProviderEvent(id, title != null ? title : "(제목 없음)", start, end, calendarName));
        }
        return events;
    }

    /** {@code start}/{@code end} 노드에서 시각을 읽는다. {@code date}만 있으면(종일) null 을 돌려 걸러지게 한다. */
    private static Instant dateTime(JsonNode node) {
        String value = text(node, "dateTime");
        if (value == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode get(String uri, String accessToken, Map<String, ?> variables) {
        try {
            JsonNode body = restClient.get()
                    .uri(uri, variables)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw providerFailure(null);
            }
            return body;
        } catch (OpenPlanException e) {
            throw e;
        } catch (Exception e) {
            // 응답 본문은 남기지 않는다 — 일정 제목과 토큰이 들어 있다.
            log.warn("구글 캘린더 호출 실패: uri={}", uri, e);
            throw providerFailure(e);
        }
    }

    private OpenPlanException providerFailure(Exception cause) {
        // AC1 — 502 E-EXT-001 에 어느 제공자인지 실어 보낸다.
        return new OpenPlanException(ErrorCode.E_EXT_001, Map.of("provider", provider().name()));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }
}
