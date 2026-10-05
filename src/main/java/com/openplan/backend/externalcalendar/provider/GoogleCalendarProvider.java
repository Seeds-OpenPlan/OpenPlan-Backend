package com.openplan.backend.externalcalendar.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
    /** 쓰기 경로가 쓰는 루트. 조회와 같은 API 지만 경로 조각을 직접 쌓아야 해 문자열로 둔다(#69). */
    private static final String CALENDAR_V3 = "https://www.googleapis.com/calendar/v3";

    /**
     * 한 번에 받아올 상한 (페이지네이션 미구현).
     *
     * <p>🔴 <b>초과분은 다음 동기화에서 따라오지 않는다.</b> {@code orderBy=startTime} 으로 앞에서부터
     * 받으므로 같은 구간을 다시 불러도 같은 앞부분이 온다 — 넘친 일정은 계속 보이지 않는다.
     * 그래서 상한에 닿으면 로그를 남긴다. 63일 구간에서 250건을 넘는 경우는 드물지만,
     * 드문 것과 없는 것은 다르다.
     */
    private static final int MAX_RESULTS = 250;

    private final RestClient restClient;

    /** 타임아웃은 {@link ExternalCalendarClientConfig} 가 정한다 — 어댑터마다 갈라지지 않게. */
    public GoogleCalendarProvider(RestClient externalCalendarRestClient) {
        this.restClient = externalCalendarRestClient;
    }

    @Override
    public ExternalCalendarProvider provider() {
        return ExternalCalendarProvider.GOOGLE;
    }

    @Override
    public List<ProviderCalendar> listCalendars(ProviderCredential credential) {
        String accessToken = credential.secret();
        JsonNode body = get(URI.create(CALENDAR_LIST_URI), accessToken);

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
    public List<ProviderEvent> listEvents(ProviderCredential credential, String externalCalendarId, String calendarName,
                                          Instant from, Instant to) {
        String accessToken = credential.secret();
        // .encode() 는 필수다 — 구글의 흔한 구독 캘린더(예: 한국 공휴일 ko.south_korea#holiday@...)는
        // ID 에 '#' 을 담는다. 인코딩 없이 조립하면 그 뒤가 URI fragment 로 분류돼 /events 경로와
        // timeMin/timeMax/singleEvents 쿼리가 통째로 요청에서 사라진다. build().encode() 관례는
        // ExternalCalendarAuthorization·OAuthLoginService 와 동일.
        //
        // 여기서 .toUri() 로 URI 객체를 바로 만드는 이유: 문자열로 만들어 RestClient.uri(String, Map)
        // 에 넘기면 그 내부 UriBuilderFactory 가 문자열을 다시 파싱·재인코딩해 이미 인코딩된 '%23'을
        // '%2523'으로 이중 인코딩한다(직접 재현 확인). URI 객체를 uri(URI) 로 바로 넘기면 절대경로
        // URI 는 재해석 없이 그대로 쓰인다.
        URI uri = UriComponentsBuilder.fromUriString(EVENTS_URI)
                .queryParam("timeMin", DateTimeFormatter.ISO_INSTANT.format(from))
                .queryParam("timeMax", DateTimeFormatter.ISO_INSTANT.format(to))
                // 반복 일정을 규칙이 아니라 개별 발생으로 펼쳐 받는다 — 고정 일정은 발생 단위로 만든다.
                .queryParam("singleEvents", "true")
                .queryParam("orderBy", "startTime")
                .queryParam("maxResults", MAX_RESULTS)
                .buildAndExpand(externalCalendarId)
                .encode()
                .toUri();

        JsonNode body = get(uri, accessToken);

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
            // recurringEventId 가 있으면 이 항목은 반복 일정의 **한 회차**다 — 밖으로 쓰지 않는다(#69).
            boolean recurring = text(item, "recurringEventId") != null;
            events.add(new ProviderEvent(id, title != null ? title : "(제목 없음)", start, end, calendarName,
                    externalCalendarId, null, text(item, "etag"), recurring));
        }
        if (body.path("items").size() >= MAX_RESULTS) {
            log.warn("구글 캘린더 일정이 조회 상한에 도달했다 — 이후 일정은 보이지 않는다: calendar={} limit={}",
                    calendarName, MAX_RESULTS);
        }
        return events;
    }

    /**
     * {@code start}/{@code end} 노드에서 시각을 읽는다. {@code date}만 있으면(종일) null 을 돌려 걸러지게 한다.
     *
     * <p>🔴 <b>파싱 실패와 종일을 구별해 로그를 남긴다</b>(2026-08-27 리뷰 지적). 둘 다 null 을 돌려주지만
     * 뜻이 다르다 — 종일은 의도된 제외이고, 파싱 실패는 <b>있는 일정이 조용히 사라지는 것</b>이다.
     * 바로 아래 {@code MAX_RESULTS} 초과 케이스는 경고를 남기는데 여기만 무음이면 관측 수단이 없다.
     */
    private static Instant dateTime(JsonNode node) {
        String value = text(node, "dateTime");
        if (value == null) {
            return null;   // 종일 일정(date 만 있음) — 의도된 제외.
        }
        try {
            return java.time.OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            log.warn("구글 캘린더 시각을 해석하지 못해 일정 한 건을 건너뛴다 — value={} ({})",
                    value, e.getClass().getSimpleName());
            return null;
        }
    }

    private JsonNode get(URI uri, String accessToken) {
        try {
            JsonNode body = restClient.get()
                    .uri(uri)
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

    // ─────────────────────────────────────────── 쓰기 (#69)

    @Override
    public ProviderWriteResult createEvent(ProviderCredential credential, String externalCalendarId,
                                           OutboundEvent event) {
        // 🔴 iCalUID 로 만든다. 구글은 같은 캘린더에 같은 iCalUID 가 이미 있으면 409 를 주는데,
        //    그것이 재시도 안전장치다 — 응답이 끊겨 다시 보내도 같은 일정이 둘 생기지 않는다.
        //    그리고 이 값이 다음 동기화의 에코 차단 근거다(OpenPlanEventUid).
        URI uri = UriComponentsBuilder.fromUriString(CALENDAR_V3)
                .pathSegment("calendars", externalCalendarId, "events")
                .build().encode().toUri();
        JsonNode created = send(HttpMethod.POST, uri, credential.secret(), null, body(event));
        return resultOf(created);
    }

    @Override
    public ProviderWriteResult updateEvent(ProviderCredential credential, String externalCalendarId,
                                           ExternalRef ref, OutboundEvent event) {
        requireEtag(ref, "수정");
        URI uri = eventUri(externalCalendarId, ref.externalEventId());
        JsonNode updated = send(HttpMethod.PATCH, uri, credential.secret(), ref.etag(), body(event));
        return resultOf(updated);
    }

    @Override
    public void deleteEvent(ProviderCredential credential, String externalCalendarId, ExternalRef ref) {
        requireEtag(ref, "삭제");
        send(HttpMethod.DELETE, eventUri(externalCalendarId, ref.externalEventId()),
                credential.secret(), ref.etag(), null);
    }

    private URI eventUri(String externalCalendarId, String eventId) {
        return UriComponentsBuilder.fromUriString(CALENDAR_V3)
                .pathSegment("calendars", externalCalendarId, "events", eventId)
                .build().encode().toUri();
    }

    /** 구글 이벤트 본문 — 우리가 채우는 것만 보낸다. PATCH 라 적지 않은 필드는 건드리지 않는다. */
    private static Map<String, Object> body(OutboundEvent event) {
        return Map.of(
                "iCalUID", event.uid(),
                "summary", event.title(),
                "start", Map.of("dateTime", event.startAt().toString()),
                "end", Map.of("dateTime", event.endAt().toString()));
    }

    private ProviderWriteResult resultOf(JsonNode body) {
        // resourceHref 는 구글에 없다 — 주소가 calendarId + eventId 로 정해진다(애플만 쓰는 값).
        return new ProviderWriteResult(text(body, "id"), null, text(body, "etag"));
    }

    private static void requireEtag(ExternalRef ref, String what) {
        if (!ref.hasEtag()) {
            // If-Match 없이 보내면 그 사이 남이 고친 것을 말없이 덮는다. 모르면 쓰지 않는다.
            throw new ProviderWriteConflictException(
                    what + "에 필요한 ETag 가 없다 — 덮어쓰지 않고 멈춘다. 다시 조회해 채운 뒤 시도할 것.");
        }
    }

    /**
     * 쓰기 한 번. 실패를 <b>세 가지로만</b> 가른다 — 호출부가 할 일이 그 셋뿐이기 때문이다.
     *
     * <ul>
     *   <li>412·409 → {@link ProviderWriteConflictException}: 덮지 않았다. 다시 읽어야 한다.</li>
     *   <li>404(DELETE) → 성공: 지우려던 결과가 이미 이루어져 있다. 실패로 올리면 아웃박스가 영원히 재시도한다.</li>
     *   <li>그 외 → 제공자 장애(502). 아웃박스가 다음 동기화에서 다시 시도한다.</li>
     * </ul>
     */
    private JsonNode send(HttpMethod method, URI uri, String accessToken, String ifMatch, Object payload) {
        try {
            var spec = restClient.method(method)
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON);
            if (ifMatch != null) {
                spec = spec.header(HttpHeaders.IF_MATCH, ifMatch);
            }
            if (payload != null) {
                return spec.contentType(MediaType.APPLICATION_JSON).body(payload)
                        .retrieve().body(JsonNode.class);
            }
            return spec.retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 412 || status == 409) {
                log.warn("구글 캘린더 쓰기 충돌: status={} uri={}", status, uri);
                throw new ProviderWriteConflictException("그 사이 외부에서 바뀌었다(status=" + status + ")");
            }
            if (status == 404 && method == HttpMethod.DELETE) {
                log.info("구글 캘린더 삭제 대상이 이미 없다 — 성공으로 친다: uri={}", uri);
                return null;
            }
            // 🔴 본문은 남기지 않는다 — 일정 제목과 토큰이 들어 있다(조회 경로와 같은 규약).
            log.warn("구글 캘린더 쓰기 실패: status={} uri={}", status, uri);
            throw providerFailure(e);
        } catch (Exception e) {
            log.warn("구글 캘린더 쓰기 호출 실패: uri={}", uri, e);
            throw providerFailure(e);
        }
    }
}
