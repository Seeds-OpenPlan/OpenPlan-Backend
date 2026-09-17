package com.openplan.backend.externalcalendar.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 밖으로 쓰는 경로 (#69 3단계).
 *
 * <p>🔴 읽기와 달리 <b>되돌릴 수 없다.</b> 잘못 쓰면 사용자의 실제 캘린더가 바뀌고 우리에게는
 * 되돌릴 재료가 없다. 그래서 여기 있는 단언은 대부분 <b>«쓰지 않는다»</b> 를 지킨다.
 */
class CalendarProviderWriteTest {

    private static final OutboundEvent EVENT = new OutboundEvent(
            "openplan-schedule-11111111-1111-4111-8111-111111111111@openplan.services",
            "알고리즘 과제; 3장, 마무리",
            Instant.parse("2026-09-22T01:00:00Z"), Instant.parse("2026-09-22T02:00:00Z"));

    @Nested
    @DisplayName("구글")
    class Google {

        private static final ProviderCredential CRED = ProviderCredential.bearer("google-token");
        private static final String CAL = "abc123@group.calendar.google.com";

        private MockRestServiceServer server;
        private GoogleCalendarProvider provider;

        @BeforeEach
        void setUp() {
            RestClient.Builder builder = RestClient.builder();
            server = MockRestServiceServer.bindTo(builder).build();
            provider = new GoogleCalendarProvider(builder.build());
        }

        @Test
        @DisplayName("생성은 iCalUID 를 실어 보낸다 — 에코 차단과 재시도 안전이 여기 달려 있다")
        void 생성은_iCalUID를_싣는다() {
            server.expect(once(), method(HttpMethod.POST))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("openplan-schedule-")))
                    .andRespond(withSuccess("{\"id\":\"evt-1\",\"etag\":\"\\\"g1\\\"\"}", MediaType.APPLICATION_JSON));

            ProviderWriteResult r = provider.createEvent(CRED, CAL, EVENT);

            assertThat(r.externalEventId()).isEqualTo("evt-1");
            assertThat(r.etag()).isEqualTo("\"g1\"");
            // 구글은 주소가 calendarId+eventId 로 정해져 resourceHref 가 없다(애플만 쓰는 값).
            assertThat(r.resourceHref()).isNull();
            server.verify();
        }

        @Test
        @DisplayName("🔴 ETag 가 없으면 수정하지 않는다 — 요청 자체를 보내지 않는다")
        void ETag가_없으면_수정하지_않는다() {
            server.expect(never(), anything());

            assertThatThrownBy(() -> provider.updateEvent(CRED, CAL,
                    new ExternalRef("evt-1", null, null), EVENT))
                    .isInstanceOf(ProviderWriteConflictException.class);

            server.verify();   // 네트워크로 나가지 않았다는 것까지 본다
        }

        @Test
        @DisplayName("수정은 If-Match 를 단다 — 없으면 그 사이 남이 고친 것을 말없이 덮는다")
        void 수정은_IfMatch를_단다() {
            server.expect(once(), method(HttpMethod.PATCH))
                    .andExpect(header("If-Match", "\"g1\""))
                    .andRespond(withSuccess("{\"id\":\"evt-1\",\"etag\":\"\\\"g2\\\"\"}", MediaType.APPLICATION_JSON));

            ProviderWriteResult r = provider.updateEvent(CRED, CAL, new ExternalRef("evt-1", null, "\"g1\""), EVENT);

            assertThat(r.etag()).as("다음 쓰기의 If-Match 재료로 새 ETag 를 받아 둔다").isEqualTo("\"g2\"");
            server.verify();
        }

        @Test
        @DisplayName("🔴 412 는 «덮지 않았다» 다 — 재시도가 아니라 다시 읽어야 한다")
        void 충돌은_덮지_않는다() {
            server.expect(once(), method(HttpMethod.PATCH))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.PRECONDITION_FAILED));

            assertThatThrownBy(() -> provider.updateEvent(CRED, CAL, new ExternalRef("evt-1", null, "\"old\""), EVENT))
                    .isInstanceOf(ProviderWriteConflictException.class);
        }

        @Test
        @DisplayName("삭제 대상이 이미 없으면 성공이다 — 실패로 올리면 아웃박스가 영원히 재시도한다")
        void 이미_없으면_삭제는_성공이다() {
            server.expect(once(), method(HttpMethod.DELETE))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

            provider.deleteEvent(CRED, CAL, new ExternalRef("evt-1", null, "\"g1\""));
            server.verify();
        }
    }

    @Nested
    @DisplayName("애플")
    class Apple {

        private static final ProviderCredential CRED = ProviderCredential.basic("me@icloud.com", "app-pw");
        private static final String CAL = "/123456/calendars/home/";

        private MockRestServiceServer server;
        private AppleCalDavProvider provider;

        @BeforeEach
        void setUp() {
            RestClient.Builder builder = RestClient.builder();
            server = MockRestServiceServer.bindTo(builder).build();
            provider = new AppleCalDavProvider(builder.build());
        }

        @Test
        @DisplayName("🔴 생성은 If-None-Match: * 를 단다 — 없으면 PUT 이 남의 일정을 통째로 덮는다")
        void 생성은_그_자리가_비었을_때만_쓴다() {
            server.expect(once(), method(HttpMethod.PUT))
                    .andExpect(header("If-None-Match", "*"))
                    .andRespond(withSuccess().header("ETag", "\"a1\""));

            ProviderWriteResult r = provider.createEvent(CRED, CAL, EVENT);

            assertThat(r.resourceHref()).as("UID 로 주소를 정해 재시도해도 같은 자리다")
                    .isEqualTo(CAL + EVENT.uid() + ".ics");
            assertThat(r.etag()).isEqualTo("\"a1\"");
            server.verify();
        }

        @Test
        @DisplayName("🔴 본문에 RRULE 이 없다 — 고정 일정도 회차로 펼쳐 넣으므로 반복 규칙을 만들지 않는다")
        void 반복_규칙을_만들지_않는다() {
            server.expect(once(), method(HttpMethod.PUT))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("RRULE"))))
                    // RFC 5545 §3.3.11 — 쉼표·세미콜론은 이스케이프돼야 본문이 깨지지 않는다.
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("알고리즘 과제\\; 3장\\, 마무리")))
                    .andRespond(withSuccess().header("ETag", "\"a1\""));

            provider.createEvent(CRED, CAL, EVENT);
            server.verify();
        }

        @Test
        @DisplayName("🔴 ETag 가 없으면 삭제하지 않는다 — 요청 자체를 보내지 않는다")
        void ETag가_없으면_삭제하지_않는다() {
            server.expect(never(), anything());

            assertThatThrownBy(() -> provider.deleteEvent(CRED, CAL,
                    new ExternalRef("uid", "/123456/calendars/home/x.ics", null)))
                    .isInstanceOf(ProviderWriteConflictException.class);

            server.verify();
        }

        @Test
        @DisplayName("412 는 «덮지 않았다» 다 — If-Match 어긋남과 이미 존재가 같은 코드로 온다")
        void 충돌은_덮지_않는다() {
            server.expect(once(), method(HttpMethod.PUT))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.PRECONDITION_FAILED));

            assertThatThrownBy(() -> provider.updateEvent(CRED, CAL,
                    new ExternalRef("uid", "/123456/calendars/home/x.ics", "\"old\""), EVENT))
                    .isInstanceOf(ProviderWriteConflictException.class);
        }

        @Test
        @DisplayName("삭제 대상이 이미 없으면 성공이다")
        void 이미_없으면_삭제는_성공이다() {
            server.expect(once(), method(HttpMethod.DELETE))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

            provider.deleteEvent(CRED, CAL, new ExternalRef("uid", "/123456/calendars/home/x.ics", "\"a1\""));
            server.verify();
        }
    }

    @Test
    @DisplayName("🔴 UID 없이는 내보낼 수 없다 — 다음 동기화가 그것을 남의 일정으로 읽는다")
    void UID_없이는_만들_수_없다() {
        assertThatThrownBy(() -> new OutboundEvent(null, "제목",
                Instant.parse("2026-09-22T01:00:00Z"), Instant.parse("2026-09-22T02:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("끝이 시작보다 앞서면 만들 수 없다")
    void 뒤집힌_구간은_거부한다() {
        assertThatThrownBy(() -> new OutboundEvent("openplan-x@openplan.services", "제목",
                Instant.parse("2026-09-22T02:00:00Z"), Instant.parse("2026-09-22T01:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
