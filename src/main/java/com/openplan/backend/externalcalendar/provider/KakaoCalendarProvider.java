package com.openplan.backend.externalcalendar.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카카오 톡캘린더 조회 (ST-B1-11 · ONB-08/09).
 *
 * <p>🔴 <b>사용 권한 승인이 선행 조건이다.</b> 공식 문서 명시 — "톡캘린더 API는 사용 권한이 주어진
 * 앱에서만 사용할 수 있으며, 사용 권한이 없는 앱 또는 테스트 앱에서는 <b>앱의 멤버만 호출</b>할 수
 * 있고 응답 범위도 해당 멤버로 제한". 승인 전에는 이 코드가 옳아도 일반 사용자에게 동작하지 않는다.
 *
 * <p>🔴 <b>조회 구간을 나눠 부른다.</b> 카카오는 {@code to}가 {@code from} 기준 <b>31일 이내</b>여야
 * 한다. 서비스의 동기화 구간은 63일(과거 7 + 향후 56)이라 그대로 부르면 거절당한다. 구글에는 없는
 * 제약이므로 <b>상위 계층을 바꾸지 않고 여기서 흡수</b>한다 — 그러려고 제공자를 인터페이스로 끊었다.
 *
 * <p>구간 경계에 걸친 일정은 두 조각 모두에 나타나므로 이벤트 id 로 합친다.
 */
@Component
public class KakaoCalendarProvider implements CalendarProvider {

    private static final Logger log = LoggerFactory.getLogger(KakaoCalendarProvider.class);

    private static final String CALENDARS_URI = "https://kapi.kakao.com/v2/api/calendar/calendars";
    private static final String EVENTS_URI = "https://kapi.kakao.com/v2/api/calendar/events";

    /** 카카오 상한은 31일. 경계에서 거절당하지 않도록 30일로 자른다. */
    private static final int WINDOW_DAYS = 30;

    /** 한 조각에서 받아올 상한 (문서상 최대 1000). */
    private static final int LIMIT = 1000;

    /**
     * {@code time.start_at}은 RFC5545 DATE-TIME 이다 — 확장형과 기본형을 모두 받는다.
     *
     * <p>{@code withZone(UTC)}가 필요하다: 패턴의 {@code 'Z'}는 리터럴이라 파싱 결과에 오프셋 정보가
     * 없고, 그대로면 {@code Instant} 변환이 실패해 <b>일정이 조용히 전부 사라진다</b>(null → 필터링).
     */
    private static final DateTimeFormatter BASIC_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final RestClient restClient;

    /** 타임아웃은 {@link ExternalCalendarClientConfig} 가 정한다 — 어댑터마다 갈라지지 않게. */
    public KakaoCalendarProvider(RestClient externalCalendarRestClient) {
        this.restClient = externalCalendarRestClient;
    }

    @Override
    public ExternalCalendarProvider provider() {
        return ExternalCalendarProvider.KAKAO;
    }

    /**
     * 내 캘린더와 구독 캘린더를 함께 돌려준다({@code filter=ALL}).
     *
     * <p>구독 캘린더(공휴일 등)를 거르지 않는 이유는 <b>무엇을 가져올지는 사용자가 고르는 것</b>이기
     * 때문이다(ONB-08). 서버가 미리 지우면 고를 기회 자체가 없어진다.
     */
    @Override
    public List<ProviderCalendar> listCalendars(String accessToken) {
        String uri = UriComponentsBuilder.fromUriString(CALENDARS_URI)
                .queryParam("filter", "ALL")
                .build().encode().toUriString();

        JsonNode body = get(uri, accessToken);

        List<ProviderCalendar> calendars = new ArrayList<>();
        collectCalendars(body.path("calendars"), calendars);
        collectCalendars(body.path("subscribe_calendars"), calendars);
        return calendars;
    }

    @Override
    public List<ProviderEvent> listEvents(String accessToken, String externalCalendarId, String calendarName,
                                          Instant from, Instant to) {
        // id 로 합친다 — 구간 경계에 걸친 일정이 두 조각에 모두 나타난다.
        Map<String, ProviderEvent> merged = new LinkedHashMap<>();

        Instant cursor = from;
        while (cursor.isBefore(to)) {
            Instant chunkEnd = cursor.plus(WINDOW_DAYS, ChronoUnit.DAYS);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            for (ProviderEvent event : fetchWindow(accessToken, externalCalendarId, calendarName, cursor, chunkEnd)) {
                merged.putIfAbsent(event.externalEventId(), event);
            }
            cursor = chunkEnd;
        }
        return List.copyOf(merged.values());
    }

    private List<ProviderEvent> fetchWindow(String accessToken, String externalCalendarId, String calendarName,
                                            Instant from, Instant to) {
        String uri = UriComponentsBuilder.fromUriString(EVENTS_URI)
                .queryParam("calendar_id", externalCalendarId)
                .queryParam("from", DateTimeFormatter.ISO_INSTANT.format(from))
                .queryParam("to", DateTimeFormatter.ISO_INSTANT.format(to))
                .queryParam("limit", LIMIT)
                .build().encode().toUriString();

        JsonNode body = get(uri, accessToken);

        List<ProviderEvent> events = new ArrayList<>();
        for (JsonNode item : body.path("events")) {
            String id = text(item, "id");
            JsonNode time = item.path("time");

            // 종일 일정은 시각이 없어 고정 일정으로 옮길 자리가 없다 — 구글과 같은 이유로 거른다.
            if (id == null || time.path("all_day").asBoolean(false)) {
                continue;
            }
            Instant start = dateTime(time, "start_at");
            Instant end = dateTime(time, "end_at");
            if (start == null || end == null || !start.isBefore(end)) {
                continue;
            }
            String title = text(item, "title");
            events.add(new ProviderEvent(id, title != null ? title : "(제목 없음)", start, end, calendarName));
        }
        if (body.path("has_next").asBoolean(false)) {
            // 페이지네이션 미구현. 다시 불러도 같은 앞부분이 오므로 넘친 일정은 계속 보이지 않는다.
            log.warn("카카오 캘린더 일정에 다음 페이지가 있다 — 이후 일정은 보이지 않는다: calendar={} limit={}",
                    calendarName, LIMIT);
        }
        return events;
    }

    private static void collectCalendars(JsonNode array, List<ProviderCalendar> target) {
        for (JsonNode item : array) {
            String id = text(item, "id");
            if (id == null) {
                continue;
            }
            String name = text(item, "name");
            target.add(new ProviderCalendar(id, name != null ? name : id));
        }
    }

    /** RFC5545 DATE-TIME — {@code 2026-08-20T01:00:00Z} 와 {@code 20260820T010000Z} 를 모두 받는다. */
    private static Instant dateTime(JsonNode time, String field) {
        String value = text(time, field);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return BASIC_UTC.parse(value, Instant::from);
            } catch (DateTimeParseException e) {
                log.warn("카카오 일정 시각을 해석할 수 없다: field={}", field);
                return null;
            }
        }
    }

    private JsonNode get(String uri, String accessToken) {
        try {
            JsonNode body = restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw providerFailure();
            }
            return body;
        } catch (OpenPlanException e) {
            throw e;
        } catch (Exception e) {
            // 응답 본문은 남기지 않는다 — 일정 제목과 토큰이 들어 있다.
            log.warn("카카오 캘린더 호출 실패: uri={}", uri, e);
            throw providerFailure();
        }
    }

    private OpenPlanException providerFailure() {
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
