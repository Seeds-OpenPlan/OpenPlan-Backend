package com.openplan.backend.externalcalendar.controller;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthTokenSet;
import com.openplan.backend.auth.oauth.OAuthUserInfo;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.provider.GoogleCalendarProvider;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
import com.openplan.backend.externalcalendar.provider.ProviderCalendar;
import com.openplan.backend.externalcalendar.provider.ProviderEvent;
import com.openplan.backend.externalcalendar.service.ExternalCalendarAuthorization;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 외부 캘린더 연동 통합 테스트 (ST-B1-11 · ONB-07/08/09 · FIX-13~17).
 *
 * <p>제공자와의 통신만 대역으로 바꾼다({@link OAuthClient}·{@link CalendarProviderRegistry}) —
 * 실제 구글을 부르면 테스트가 네트워크와 남의 서비스에 묶인다. <b>그 바깥은 전부 실제로 돈다</b>:
 * 토큰 암호화, DB 쓰기, 소유자 격리, CASCADE, FIX-16 미러, 고정 일정 생성.
 */
@SpringBootTest(properties = {
        "op.external-calendar.token-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "op.oauth.clients.google.client-id=test-google-client",
        "op.oauth.clients.google.client-secret=test-google-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ExternalCalendarApiTest {

    private static final String CONNECTIONS = "/api/v1/external-calendar-connections";
    private static final UUID MAIN = UUID.fromString("eeee1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("eeee1111-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private OAuthClient oauthClient;
    @MockitoBean
    private CalendarProviderRegistry providerRegistry;
    /** 제공자 구현이 둘(구글·카카오)이라 구체 타입으로 지정한다 — {@code CalendarProvider} 로는 대역 대상이 모호하다. */
    @MockitoBean
    private GoogleCalendarProvider googleProvider;

    /**
     * state 서명은 {@link ExternalCalendarAuthorization} 자체 단위 테스트가 검증한다. 여기서 대역으로 두는
     * 이유는 이 테스트가 dev-stub(X-Dev-User)으로 도는데 그 프로파일에는 JwtService 빈이 없기 때문이다(D-32).
     */
    @MockitoBean
    private ExternalCalendarAuthorization authorization;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM external_calendar_connections WHERE user_id IN (?, ?)", MAIN, OTHER);

        given(providerRegistry.supports(any())).willReturn(true);
        given(providerRegistry.get(any())).willReturn(googleProvider);
        given(googleProvider.provider()).willReturn(ExternalCalendarProvider.GOOGLE);

        given(oauthClient.exchangeCodeForTokens(any(), any(), any(), any()))
                .willReturn(new OAuthTokenSet("provider-access", "provider-refresh", 3600L));
        given(oauthClient.fetchUserInfo(any(), anyString()))
                .willReturn(new OAuthUserInfo("google-user-1", "owner@gmail.com"));
    }

    // ---------- ONB-07 · FIX-14 연동 추가 ----------

    @Test
    @DisplayName("연동 추가 → 201 · status 는 계약 어휘 CONNECTED · 토큰은 응답에 없다")
    void createOk() throws Exception {
        mockMvc.perform(connect(MAIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.connectionId").exists())
                .andExpect(jsonPath("$.data.provider").value("GOOGLE"))
                .andExpect(jsonPath("$.data.accountIdentifier").value("owner@gmail.com"))
                .andExpect(jsonPath("$.data.status").value("CONNECTED"))
                .andExpect(jsonPath("$.data.accessTokenEnc").doesNotExist())
                .andExpect(jsonPath("$.data.refreshTokenEnc").doesNotExist());
    }

    @Test
    @DisplayName("제공자 토큰은 평문으로 저장되지 않는다 — DB 를 직접 읽어 확인")
    void storesTokensEncrypted() throws Exception {
        UUID connectionId = createConnection(MAIN);

        String stored = jdbc.queryForObject(
                "SELECT access_token_enc FROM external_calendar_connections WHERE connection_id = ?",
                String.class, connectionId);

        assertThat(stored).isNotNull().isNotEqualTo("provider-access");
    }

    @Test
    @DisplayName("같은 계정 재연결 → 409 E-EXT-004")
    void duplicateConnectionConflicts() throws Exception {
        createConnection(MAIN);

        mockMvc.perform(connect(MAIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-EXT-004"));
    }

    @Test
    @DisplayName("제공자 오류 → 502 E-EXT-001")
    void providerFailureIsBadGateway() throws Exception {
        UUID connectionId = createConnection(MAIN);
        willThrow(new OpenPlanException(ErrorCode.E_EXT_001, Map.of("provider", "GOOGLE")))
                .given(googleProvider).listCalendars(any());

        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/calendars").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("E-EXT-001"));
    }

    // ---------- FIX-13 목록 · 소유자 격리 ----------

    @Test
    @DisplayName("목록은 내 연동만 — 타인 연동은 보이지 않는다")
    void listIsScopedToOwner() throws Exception {
        createConnection(MAIN);

        mockMvc.perform(get(CONNECTIONS).header("X-Dev-User", OTHER.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("타인 연동 접근 → 404 (부재와 같은 응답 — 존재를 알려주지 않는다)")
    void otherUsersConnectionIsNotFound() throws Exception {
        UUID connectionId = createConnection(MAIN);

        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/calendars").header("X-Dev-User", OTHER.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- ONB-08 캘린더 목록·선택 ----------

    @Test
    @DisplayName("캘린더 목록 — 이미 선택한 것은 selected=true")
    void listCalendarsMarksSelected() throws Exception {
        UUID connectionId = createConnection(MAIN);
        given(googleProvider.listCalendars(any())).willReturn(List.of(
                new ProviderCalendar("cal-1", "내 캘린더"),
                new ProviderCalendar("cal-2", "회사")));
        saveSelections(connectionId, """
                {"selections":[{"externalCalendarId":"cal-1","name":"내 캘린더"}]}""");

        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/calendars").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].externalCalendarId").value("cal-1"))
                .andExpect(jsonPath("$.data[0].selected").value(true))
                .andExpect(jsonPath("$.data[1].selected").value(false));
    }

    @Test
    @DisplayName("선택 저장은 전체 교체 — 목록에서 빠진 캘린더는 삭제된다 (FIX-15)")
    void saveSelectionsReplacesWholeSet() throws Exception {
        UUID connectionId = createConnection(MAIN);
        saveSelections(connectionId, """
                {"selections":[{"externalCalendarId":"cal-1","name":"내 캘린더"},
                               {"externalCalendarId":"cal-2","name":"회사"}]}""");

        saveSelections(connectionId, """
                {"selections":[{"externalCalendarId":"cal-2","name":"회사"}]}""");

        List<String> remaining = jdbc.queryForList(
                "SELECT external_calendar_id FROM external_calendar_selections WHERE connection_id = ?",
                String.class, connectionId);
        assertThat(remaining).containsExactly("cal-2");
    }

    @Test
    @DisplayName("빈 배열도 유효하다 — '아무것도 가져오지 않음'")
    void emptySelectionIsValid() throws Exception {
        UUID connectionId = createConnection(MAIN);
        saveSelections(connectionId, """
                {"selections":[{"externalCalendarId":"cal-1","name":"내 캘린더"}]}""");

        saveSelections(connectionId, """
                {"selections":[]}""");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM external_calendar_selections WHERE connection_id = ?",
                Integer.class, connectionId);
        assertThat(count).isZero();
    }

    // ---------- ONB-08/09 동기화·반영 ----------

    @Test
    @DisplayName("일정 조회가 곧 동기화 — 후보가 CANDIDATE 로 쌓인다")
    void listEventsSynchronizes() throws Exception {
        UUID connectionId = connectionWithCalendar();
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(providerEvent("ext-1", "회의")));

        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/events").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("회의"))
                .andExpect(jsonPath("$.data[0].applyStatus").value("CANDIDATE"));
    }

    @Test
    @DisplayName("재동기화는 같은 행을 갱신한다 — 제외한 일정이 후보로 되살아나지 않는다")
    void resyncPreservesUserDecision() throws Exception {
        UUID connectionId = connectionWithCalendar();
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(providerEvent("ext-1", "회의")));
        UUID eventId = firstEventId(connectionId);
        applyEvent(eventId, """
                {"mode":"EXCLUDE"}""");

        // 같은 원본 일정을 다시 가져온다
        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/events").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].applyStatus").value("EXCLUDED"));
    }

    @Test
    @DisplayName("AS_IS 반영 → 201 · 고정 일정이 source=EXTERNAL 로 생긴다 (AC2)")
    void applyAsIsCreatesExternalFixedSchedule() throws Exception {
        UUID connectionId = connectionWithCalendar();
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(providerEvent("ext-1", "회의")));
        UUID eventId = firstEventId(connectionId);

        applyEvent(eventId, """
                {"mode":"AS_IS"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applyStatus").value("APPLIED"))
                .andExpect(jsonPath("$.data.fixedSchedule.fixedScheduleId").exists())
                .andExpect(jsonPath("$.data.fixedSchedule.title").value("회의"))
                .andExpect(jsonPath("$.data.fixedSchedule.source").value("EXTERNAL"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT title, source, connection_id FROM fixed_schedules WHERE connection_id = ?", connectionId);
        assertThat(row).containsEntry("title", "회의").containsEntry("source", "EXTERNAL");
    }

    @Test
    @DisplayName("EXCLUDE 는 고정 일정을 만들지 않는다")
    void applyExcludeCreatesNothing() throws Exception {
        UUID connectionId = connectionWithCalendar();
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(providerEvent("ext-1", "회의")));
        UUID eventId = firstEventId(connectionId);

        applyEvent(eventId, """
                {"mode":"EXCLUDE"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.applyStatus").value("EXCLUDED"))
                .andExpect(jsonPath("$.data.fixedSchedule").doesNotExist());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM fixed_schedules WHERE connection_id = ?", Integer.class, connectionId);
        assertThat(count).isZero();
    }

    // ---------- FIX-16 미러 · FIX-17 해제 ----------

    @Test
    @DisplayName("연동 비활성 → 유래 고정 일정이 같은 트랜잭션에서 INACTIVE 가 된다 (AC3)")
    void disablingMirrorsToFixedSchedules() throws Exception {
        UUID connectionId = appliedConnection();

        mockMvc.perform(patch(CONNECTIONS + "/" + connectionId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        String status = jdbc.queryForObject(
                "SELECT status FROM fixed_schedules WHERE connection_id = ?", String.class, connectionId);
        assertThat(status).isEqualTo("INACTIVE");
    }

    @Test
    @DisplayName("재활성하면 ACTIVE 로 돌아온다 (AC3)")
    void reEnablingRestoresActive() throws Exception {
        UUID connectionId = appliedConnection();
        changeStatus(connectionId, "DISABLED");

        changeStatus(connectionId, "CONNECTED");

        String status = jdbc.queryForObject(
                "SELECT status FROM fixed_schedules WHERE connection_id = ?", String.class, connectionId);
        assertThat(status).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("연동 해제 → 204 · 유래 고정 일정이 함께 사라진다 (FIX-17 · AC4)")
    void deleteCascadesFixedSchedules() throws Exception {
        UUID connectionId = appliedConnection();

        mockMvc.perform(delete(CONNECTIONS + "/" + connectionId).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        Integer schedules = jdbc.queryForObject(
                "SELECT count(*) FROM fixed_schedules WHERE connection_id = ?", Integer.class, connectionId);
        assertThat(schedules).isZero();
    }

    @Test
    @DisplayName("알 수 없는 상태값 → 422")
    void unknownStatusIsUnprocessable() throws Exception {
        UUID connectionId = createConnection(MAIN);

        mockMvc.perform(patch(CONNECTIONS + "/" + connectionId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ACTIVE"}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.RequestBuilder connect(UUID userId) {
        return post(CONNECTIONS).header("X-Dev-User", userId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"provider":"GOOGLE","authCode":"code-1","redirectUri":"http://localhost/cb","state":"signed-state"}""");
    }

    private UUID createConnection(UUID userId) throws Exception {
        mockMvc.perform(connect(userId)).andExpect(status().isCreated());
        return jdbc.queryForObject(
                "SELECT connection_id FROM external_calendar_connections WHERE user_id = ?", UUID.class, userId);
    }

    /** 연결 + 캘린더 1개 선택까지 마친 상태. */
    @Test
    @DisplayName("동기화가 겹쳐도 500 이 아니다 — 진 쪽은 조용히 물러나고 이긴 쪽의 행을 본다")
    void 동기화_경합은_500이_아니다() throws Exception {
        UUID connectionId = connectionWithCalendar();

        // 제공자 호출은 synchronize() 가 기존 행을 **읽은 뒤** 일어난다. 정확히 그 틈에서
        // 다른 요청(탭 두 개·중복 새로고침)이 같은 (connection_id, external_event_id) 를 먼저
        // 커밋한 상태를 만든다. 별도 커넥션 + autoCommit 이라 이 요청의 트랜잭션 밖에서 커밋된다 —
        // JdbcTemplate 을 그대로 쓰면 같은 트랜잭션에 참여해 "다른 요청" 이 되지 않는다.
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willAnswer(invocation -> {
                    insertOutOfBand(connectionId, "ext-race", "먼저 들어온 회의");
                    return List.of(providerEvent("ext-race", "나중에 온 회의"));
                });

        // 옛 구현은 여기서 500 이었다. saveAll 만 쓰면 UQ 위반이 catch 밖(다음 조회의 auto-flush)에서
        // 터지고, saveAllAndFlush 로 안에서 받아도 실패한 flush 가 트랜잭션을 rollback-only 로 표시해
        // 커밋에서 다시 터진다 — 두 경우 모두 사용자에게는 근거 없는 500 이다.
        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/events").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("먼저 들어온 회의"));
    }

    /** 이 요청의 트랜잭션 <b>밖에서</b> 커밋한다 — 경합 상대를 흉내 내려면 별도 커넥션이어야 한다. */
    private void insertOutOfBand(UUID connectionId, String externalEventId, String title) throws Exception {
        try (Connection connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            connection.setAutoCommit(true);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO external_calendar_events
                        (external_calendar_event_id, external_event_id, connection_id, title,
                         start_at, end_at, source_calendar, apply_status, synced_at)
                    VALUES (?, ?, ?, ?, ?, ?, '내 캘린더', 'CANDIDATE', now())
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setString(2, externalEventId);
                statement.setObject(3, connectionId);
                statement.setString(4, title);
                statement.setTimestamp(5, Timestamp.from(Instant.parse("2026-08-20T01:00:00Z")));
                statement.setTimestamp(6, Timestamp.from(Instant.parse("2026-08-20T02:00:00Z")));
                statement.executeUpdate();
            }
        }
    }

    // ---------- #68 원격 수정·삭제 전파 (실 DB — 새 JPQL·derived query 를 실제로 태운다) ----------

    /**
     * 동기화 창 **안**의 일정.
     *
     * <p>🔴 기존 {@link #providerEvent} 는 2026-08-20 고정인데, 그 날짜는 시간이 지나면 창(과거 7일 ~
     * 미래 56일) 밖으로 밀려난다. 창 밖 일정은 <b>삭제 판정에서 제외</b>되므로(그게 옳다) 그대로 쓰면
     * 이 테스트가 "가드가 막았다" 를 "쿼리가 돌았다" 로 착각하게 된다. 오늘 기준 상대 날짜로 만든다.
     */
    private static ProviderEvent inWindowEvent(String externalId, String title, int hourOfDayUtc) {
        Instant day = Instant.now().plus(3, java.time.temporal.ChronoUnit.DAYS)
                .truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        Instant start = day.plus(hourOfDayUtc, java.time.temporal.ChronoUnit.HOURS);
        // 🔴 캘린더 **id**("cal-1")를 실어야 삭제 귀속이 성립한다. 이름만 실으면 id 가 null 이라
        //    삭제 대상에서 제외되고, 이 테스트는 "가드가 막았다" 를 보게 된다.
        return new ProviderEvent(externalId, title, start, start.plus(1, java.time.temporal.ChronoUnit.HOURS),
                "내 캘린더", "cal-1");
    }

    @Test
    @DisplayName("원격에서 사라지면 반영된 고정 일정까지 지운다 — 새 JPQL 을 실 DB 에서 태운다")
    void remoteDeletionRemovesFixedSchedule() throws Exception {
        UUID connectionId = connectionWithCalendar();
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(inWindowEvent("ext-del", "지워질 회의", 1)));
        UUID eventId = firstEventId(connectionId);
        applyEvent(eventId, """
                {"mode":"AS_IS"}""").andExpect(status().isCreated());

        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM fixed_schedules WHERE external_calendar_event_id = ?", Integer.class, eventId);
        assertThat(before).as("반영이 링크를 남겼는가").isEqualTo(1);

        // 원격에서 지워졌다 — 이번 조회는 아무것도 돌려주지 않는다.
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of());
        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/events").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        Integer after = jdbc.queryForObject(
                "SELECT count(*) FROM fixed_schedules WHERE external_calendar_event_id = ?", Integer.class, eventId);
        assertThat(after).as("고정 일정도 함께 사라져야 한다").isZero();
    }

    @Test
    @DisplayName("원격에서 시간이 바뀌면 반영된 고정 일정이 따라 바뀐다 — derived query 를 실 DB 에서 태운다")
    void remoteUpdateMovesFixedSchedule() throws Exception {
        UUID connectionId = connectionWithCalendar();
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(inWindowEvent("ext-upd", "옮겨질 회의", 1)));
        UUID eventId = firstEventId(connectionId);
        applyEvent(eventId, """
                {"mode":"AS_IS"}""").andExpect(status().isCreated());

        String beforeStart = jdbc.queryForObject(
                "SELECT start_time::text FROM fixed_schedules WHERE external_calendar_event_id = ?",
                String.class, eventId);

        // 같은 일정이 3시간 뒤로 옮겨졌다.
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(inWindowEvent("ext-upd", "옮겨질 회의", 4)));
        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/events").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk());

        String afterStart = jdbc.queryForObject(
                "SELECT start_time::text FROM fixed_schedules WHERE external_calendar_event_id = ?",
                String.class, eventId);
        assertThat(afterStart).as("원격이 옮겨졌으면 고정 일정도 옮겨져야 한다").isNotEqualTo(beforeStart);
    }

    private UUID connectionWithCalendar() throws Exception {
        UUID connectionId = createConnection(MAIN);
        saveSelections(connectionId, """
                {"selections":[{"externalCalendarId":"cal-1","name":"내 캘린더"}]}""");
        return connectionId;
    }

    /** 연결 + 선택 + 일정 1건을 AS_IS 로 반영해 고정 일정이 있는 상태. */
    private UUID appliedConnection() throws Exception {
        UUID connectionId = connectionWithCalendar();
        given(googleProvider.listEvents(any(), eq("cal-1"), anyString(), any(), any()))
                .willReturn(List.of(providerEvent("ext-1", "회의")));
        applyEvent(firstEventId(connectionId), """
                {"mode":"AS_IS"}""").andExpect(status().isCreated());
        return connectionId;
    }

    private void saveSelections(UUID connectionId, String body) throws Exception {
        mockMvc.perform(put(CONNECTIONS + "/" + connectionId + "/calendar-selections")
                        .header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void changeStatus(UUID connectionId, String status) throws Exception {
        mockMvc.perform(patch(CONNECTIONS + "/" + connectionId).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions applyEvent(UUID eventId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/external-calendar-events/" + eventId + "/application")
                .header("X-Dev-User", MAIN.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 동기화를 한 번 돌려 후보를 만든 뒤 그 id 를 돌려준다. */
    private UUID firstEventId(UUID connectionId) throws Exception {
        mockMvc.perform(get(CONNECTIONS + "/" + connectionId + "/events").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk());
        return jdbc.queryForObject(
                "SELECT external_calendar_event_id FROM external_calendar_events WHERE connection_id = ?",
                UUID.class, connectionId);
    }

    private static ProviderEvent providerEvent(String externalId, String title) {
        // 2026-08-20(목) 10:00~11:00 KST
        return new ProviderEvent(externalId, title,
                Instant.parse("2026-08-20T01:00:00Z"), Instant.parse("2026-08-20T02:00:00Z"), "내 캘린더");
    }

    private void seedUser(UUID id) {
        jdbc.update("""
                INSERT INTO users (user_id, email, password_hash, login_type, is_email_verified, status)
                VALUES (?, ?, 'x', 'LOCAL', true, 'ACTIVE') ON CONFLICT (user_id) DO NOTHING
                """, id, id + "@test.local");
        jdbc.update("""
                INSERT INTO user_profiles (profile_id, user_id, name, purpose, timezone, week_start_day)
                VALUES (?, ?, '테스트', '테스트', 'Asia/Seoul', 'MON') ON CONFLICT (user_id) DO NOTHING
                """, UUID.randomUUID(), id);
    }
}
