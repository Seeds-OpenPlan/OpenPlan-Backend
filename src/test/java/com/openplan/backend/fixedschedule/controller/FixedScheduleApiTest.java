package com.openplan.backend.fixedschedule.controller;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고정 일정 생성·목록 API 통합 테스트 (FIX-05·FIX-04). MANUAL 생성(201·source/status·5분 단위·시작<종료)과
 * 목록(정렬·status 필터·소유자 스코프)을 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class FixedScheduleApiTest {

    private static final String PATH = "/api/v1/fixed-schedules";
    private static final UUID MAIN = UUID.fromString("cccc1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("cccc1111-0000-0000-0000-000000000002");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM fixed_schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
        // 연동을 지우면 회차·아웃박스 행도 CASCADE 로 함께 지워진다.
        jdbc.update("DELETE FROM external_calendar_connections WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------- POST 생성 ----------

    @Test
    @DisplayName("생성 → 201 · source=MANUAL · status=ACTIVE · version=0 · 입력 반영")
    void createOk() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:00","endTime":"10:30"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fixedScheduleId").exists())
                .andExpect(jsonPath("$.data.title").value("수업"))
                .andExpect(jsonPath("$.data.weekday").value("MON"))
                .andExpect(jsonPath("$.data.startTime").value("09:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("10:30:00"))
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("기간 한정(startDate·endDate) 포함 생성 → 201 · 날짜 반영")
    void createWithDateRange() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"특강","weekday":"WED","startTime":"14:00","endTime":"16:00",
                                 "startDate":"2026-09-01","endDate":"2026-11-30"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.endDate").value("2026-11-30"));
    }

    @Test
    @DisplayName("제목 누락 → 422 E-COM-009 (title.required)")
    void titleRequired() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weekday":"MON","startTime":"09:00","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("title"));
    }

    @Test
    @DisplayName("미정의 요일 → 422 E-COM-009 (weekday.invalid, 500 아님)")
    void invalidWeekday() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MONDAY","startTime":"09:00","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("weekday"));
    }

    @Test
    @DisplayName("5분 단위 아님 → 422 E-COM-009 (startTime.step)")
    void notFiveMinuteStep() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:03","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("startTime"));
    }

    @Test
    @DisplayName("시작 >= 종료 → 422 E-COM-009 (endTime.range)")
    void startNotBeforeEnd() throws Exception {
        mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"10:00","endTime":"10:00"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("endTime"));
    }

    // ---------- PATCH 편집 ----------

    @Test
    @DisplayName("편집 → 200 · 필드 교체 · version 증가(0→1)")
    void updateOk() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업(변경)","weekday":"TUE","startTime":"11:00","endTime":"12:30","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("수업(변경)"))
                .andExpect(jsonPath("$.data.weekday").value("TUE"))
                .andExpect(jsonPath("$.data.startTime").value("11:00:00"))
                .andExpect(jsonPath("$.data.endTime").value("12:30:00"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("부분 수정 — title만 보내면 나머지(요일·시각)는 그대로 유지")
    void updateTitleOnly() throws Exception {
        UUID id = create(MAIN, "수영", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수영가기","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("수영가기")) // 바뀜
                .andExpect(jsonPath("$.data.weekday").value("MON"))    // 유지
                .andExpect(jsonPath("$.data.startTime").value("09:00:00")) // 유지
                .andExpect(jsonPath("$.data.endTime").value("10:00:00"))   // 유지
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("부분 수정 — 생략한 기간(startDate/endDate)은 기존 값 유지")
    void updateKeepsOmittedDateRange() throws Exception {
        String body = mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"특강","weekday":"WED","startTime":"14:00","endTime":"16:00",
                                 "startDate":"2026-09-01","endDate":"2026-11-30"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.data.fixedScheduleId"));

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"특강(변경)","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value("2026-09-01")) // 유지
                .andExpect(jsonPath("$.data.endDate").value("2026-11-30"));  // 유지
    }

    @Test
    @DisplayName("부분 수정 — startDate를 명시적 null로 보내면 해제됨")
    void updateClearsDateWithExplicitNull() throws Exception {
        String body = mockMvc.perform(post(PATH).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"특강","weekday":"WED","startTime":"14:00","endTime":"16:00",
                                 "startDate":"2026-09-01","endDate":"2026-11-30"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.data.fixedScheduleId"));

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startDate":null,"endDate":null,"version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").doesNotExist())
                .andExpect(jsonPath("$.data.endDate").doesNotExist());
    }

    @Test
    @DisplayName("부분 수정 — 한쪽 시각만 바꿔 시작>=종료가 되면 422 (병합 쌍 검증)")
    void updatePartialTimeStillValidatesPair() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"startTime":"11:00","version":0}""")) // endTime(10:00)은 유지 → 11:00 >= 10:00
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("endTime"));
    }

    @Test
    @DisplayName("없는 id 편집 → 404 E-COM-004")
    void updateNotFound() throws Exception {
        mockMvc.perform(patch(PATH + "/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","weekday":"MON","startTime":"09:00","endTime":"10:00","version":0}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 고정 일정 편집 → 404 (존재 은닉)")
    void updateOtherUserHidden() throws Exception {
        UUID id = create(OTHER, "남의수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","weekday":"MON","startTime":"09:00","endTime":"10:00","version":0}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("version 불일치 → 409 E-COM-006 (latest 동봉)")
    void updateVersionConflict() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:00","endTime":"10:00","version":5}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-COM-006"))
                .andExpect(jsonPath("$.error.details.latest.fixedScheduleId").value(id.toString()))
                .andExpect(jsonPath("$.error.details.latest.version").value(0));
    }

    @Test
    @DisplayName("version 누락 → 400 (낙관락 입력 필수)")
    void updateVersionRequired() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:00","endTime":"10:00"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("편집 값 오류(5분 단위 아님) → 422 E-COM-009")
    void updateInvalidField() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(patch(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"수업","weekday":"MON","startTime":"09:03","endTime":"10:00","version":0}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("startTime"));
    }

    // ---------- DELETE 삭제 ----------

    @Test
    @DisplayName("삭제 → 204 · 이후 목록에서 사라짐")
    void deleteOk() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("없는 id 삭제 → 404 E-COM-004 (재삭제도 404 — 멱등 아님)")
    void deleteNotFound() throws Exception {
        mockMvc.perform(delete(PATH + "/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 고정 일정 삭제 → 404 (존재 은닉, 남의 것 안 지워짐)")
    void deleteOtherUserHidden() throws Exception {
        UUID id = create(OTHER, "남의수업", "MON", "09:00", "10:00");

        mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        mockMvc.perform(get(PATH).header("X-Dev-User", OTHER.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)); // 남의 것은 그대로
    }

    @Test
    @DisplayName("삭제 시 주차 예외 행도 FK CASCADE로 함께 삭제됨")
    void deleteCascadesWeekExceptions() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");
        jdbc.update("""
                INSERT INTO fixed_schedule_week_exceptions (exception_id, fixed_schedule_id, week_start_date)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), id, java.time.LocalDate.of(2026, 8, 17));

        mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixed_schedule_week_exceptions WHERE fixed_schedule_id = ?",
                Integer.class, id);
        org.junit.jupiter.api.Assertions.assertEquals(0, remaining);
    }

    /**
     * 🔴 QA 2026-10-21 F4 회귀 — 회차 행이 CASCADE 로 사라지기 <b>전에</b> 외부 삭제가 적혀야 한다.
     * 단위 테스트(FixedOccurrenceReconcilerTest)는 리포지토리를 mock 으로 대신해 실제 CASCADE 순서와
     * 컬럼 매핑을 보지 못한다(#97 리뷰 Should-fix). 여기서는 실제 스키마로 끝까지 따라간다.
     */
    @Test
    @DisplayName("🔴 내보낸 회차가 있는 고정 일정 삭제 → 나간 회차는 DELETE 가 보낸 캘린더로 적재되고, 대기 CREATE 는 거둬진다")
    void deleteQueuesOutboundDeleteForExportedOccurrences() throws Exception {
        UUID id = create(MAIN, "수업", "FRI", "19:00", "20:00");
        UUID connection = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO external_calendar_connections
                    (connection_id, user_id, provider, account_identifier, sync_mode, status,
                     connected_at, created_at, access_token_enc, refresh_token_enc, granted_scope, write_calendar_id)
                VALUES (?, ?, 'GOOGLE', 'tester@gmail.com', 'MANUAL', 'ACTIVE', now(), now(), 'enc', 'renc',
                        'openid email https://www.googleapis.com/auth/calendar.events', 'cal-지금것')
                """, connection, MAIN);
        // 이미 구글에 나간 회차 — 대상 설정을 바꾼 뒤라 «있는 곳» 이 지금 설정과 다르다.
        UUID sent = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fixed_schedule_occurrences
                    (occurrence_id, fixed_schedule_id, user_id, connection_id, occurrence_date, external_uid,
                     external_event_id, etag, sent_title, sent_start_at, sent_end_at, sent_calendar_id)
                VALUES (?, ?, ?, ?, DATE '2026-10-23', ?, 'evt-1', 'etag-1', '수업', now(), now(), 'cal-옛것')
                """, sent, id, MAIN, connection, "uid-sent-" + sent);
        // 아직 안 나간 회차와 그 대기 CREATE — 남겨 두면 지운 뒤에 뒤늦게 나가 유령이 된다.
        UUID unsent = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fixed_schedule_occurrences
                    (occurrence_id, fixed_schedule_id, user_id, connection_id, occurrence_date, external_uid)
                VALUES (?, ?, ?, ?, DATE '2026-10-30', ?)
                """, unsent, id, MAIN, connection, "uid-unsent-" + unsent);
        jdbc.update("""
                INSERT INTO outbound_calendar_ops
                    (op_id, user_id, connection_id, target_type, target_id, operation, payload, status)
                VALUES (?, ?, ?, 'FIXED_OCCURRENCE', ?, 'CREATE', CAST(? AS jsonb), 'PENDING')
                """, UUID.randomUUID(), MAIN, connection, unsent,
                "{\"uid\":\"uid-unsent\",\"title\":\"수업\",\"writeCalendarId\":\"cal-지금것\"}");

        mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        var queued = jdbc.queryForList("""
                SELECT operation, status, payload->>'writeCalendarId' AS cal, payload->>'externalEventId' AS evt
                  FROM outbound_calendar_ops WHERE target_id = ?
                """, sent);
        org.junit.jupiter.api.Assertions.assertEquals(1, queued.size());
        org.junit.jupiter.api.Assertions.assertEquals("DELETE", queued.getFirst().get("operation"));
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", queued.getFirst().get("status"));
        // 지금 설정(cal-지금것)이 아니라 이벤트가 실재하는 곳 — 회차 행이 사라져 payload 가 유일한 근거다.
        org.junit.jupiter.api.Assertions.assertEquals("cal-옛것", queued.getFirst().get("cal"));
        org.junit.jupiter.api.Assertions.assertEquals("evt-1", queued.getFirst().get("evt"));

        Integer pendingCreates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbound_calendar_ops WHERE target_id = ?", Integer.class, unsent);
        org.junit.jupiter.api.Assertions.assertEquals(0, pendingCreates);

        Integer occurrences = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixed_schedule_occurrences WHERE fixed_schedule_id = ?", Integer.class, id);
        org.junit.jupiter.api.Assertions.assertEquals(0, occurrences);
    }

    /**
     * 🔴 #97 리뷰 Should-fix — 동기화가 회차의 CREATE 를 <b>보내는 중에</b> 고정 일정을 지운다.
     * 실행기는 매핑 행을 잠근 채 보내므로, 삭제는 그 커밋을 기다렸다가 «나갔음» 을 보고 DELETE 를 적어야 한다.
     * 잠그지 않고 읽으면 «안 나감» 으로 보고 CREATE 만 거둔 뒤 회차를 CASCADE 로 지워, 방금 밖에 생긴 이벤트를
     * 아무도 지울 수 없게 된다. 여기서는 실행기를 «행을 잠그고 → (보내고) → 결과를 적고 커밋» 하는 트랜잭션으로 흉내 낸다.
     */
    @Test
    @DisplayName("🔴 회차를 내보내는 중에 고정 일정을 지우면 → 전송 커밋을 기다렸다가 그 이벤트의 DELETE 를 적는다")
    void deleteWaitsForInFlightSendAndQueuesDelete() throws Exception {
        UUID id = create(MAIN, "수업", "FRI", "19:00", "20:00");
        UUID connection = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO external_calendar_connections
                    (connection_id, user_id, provider, account_identifier, sync_mode, status,
                     connected_at, created_at, access_token_enc, refresh_token_enc, granted_scope, write_calendar_id)
                VALUES (?, ?, 'GOOGLE', 'tester@gmail.com', 'MANUAL', 'ACTIVE', now(), now(), 'enc', 'renc',
                        'openid email https://www.googleapis.com/auth/calendar.events', 'cal-지금것')
                """, connection, MAIN);
        UUID occurrence = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fixed_schedule_occurrences
                    (occurrence_id, fixed_schedule_id, user_id, connection_id, occurrence_date, external_uid)
                VALUES (?, ?, ?, ?, DATE '2026-10-23', ?)
                """, occurrence, id, MAIN, connection, "uid-inflight-" + occurrence);
        UUID createOp = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbound_calendar_ops
                    (op_id, user_id, connection_id, target_type, target_id, operation, payload, status)
                VALUES (?, ?, ?, 'FIXED_OCCURRENCE', ?, 'CREATE', CAST(? AS jsonb), 'PENDING')
                """, createOp, MAIN, connection, occurrence,
                "{\"uid\":\"uid-inflight\",\"title\":\"수업\",\"writeCalendarId\":\"cal-지금것\"}");

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch providerReturned = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT occurrence_id FROM fixed_schedule_occurrences WHERE occurrence_id = ? FOR UPDATE",
                    occurrence);
            locked.countDown();
            try {
                providerReturned.await(10, TimeUnit.SECONDS);   // 제공자 호출 중
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            jdbc.update("""
                    UPDATE fixed_schedule_occurrences
                       SET external_event_id = 'evt-inflight', etag = 'etag-inflight', sent_title = '수업',
                           sent_start_at = now(), sent_end_at = now(), sent_calendar_id = 'cal-지금것'
                     WHERE occurrence_id = ?
                    """, occurrence);
            jdbc.update("UPDATE outbound_calendar_ops SET status = 'DONE' WHERE op_id = ?", createOp);
        }));
        org.junit.jupiter.api.Assertions.assertTrue(locked.await(10, TimeUnit.SECONDS));

        CompletableFuture<Integer> deleting = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(delete(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                        .andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // 삭제가 실제로 회차 잠금 앞에 서 있는지 DB 에서 확인한 뒤에 전송을 끝낸다 — 잠금이 없으면 삭제는
        // 기다리지 않고 지나가 버리고, 이 대기는 끝내 성립하지 않는다.
        boolean deleteWaited = false;
        for (int i = 0; i < 100 && !deleteWaited && !deleting.isDone(); i++) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Integer.class);
            deleteWaited = waiting != null && waiting > 0;
            if (!deleteWaited) {
                Thread.sleep(50);
            }
        }
        providerReturned.countDown();
        sending.get(10, TimeUnit.SECONDS);
        org.junit.jupiter.api.Assertions.assertEquals(204, deleting.get(10, TimeUnit.SECONDS));
        org.junit.jupiter.api.Assertions.assertTrue(deleteWaited, "삭제가 보내는 중인 회차의 잠금을 기다리지 않았다");

        var queued = jdbc.queryForList("""
                SELECT operation, status, payload->>'writeCalendarId' AS cal, payload->>'externalEventId' AS evt
                  FROM outbound_calendar_ops WHERE target_id = ? AND operation = 'DELETE'
                """, occurrence);
        org.junit.jupiter.api.Assertions.assertEquals(1, queued.size(), "방금 밖에 생긴 이벤트의 DELETE 가 없다 — 유령이 남는다");
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", queued.getFirst().get("status"));
        org.junit.jupiter.api.Assertions.assertEquals("cal-지금것", queued.getFirst().get("cal"));
        org.junit.jupiter.api.Assertions.assertEquals("evt-inflight", queued.getFirst().get("evt"));
    }

    // ---------- 주차 예외 (PLAN-33/34) ----------

    @Test
    @DisplayName("주차 비활성화 → 201 · data{fixedScheduleId,weekStartDate}")
    void addWeekExceptionCreated() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(post(PATH + "/" + id + "/week-exceptions").header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weekStartDate":"2026-08-17"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.fixedScheduleId").value(id.toString()))
                .andExpect(jsonPath("$.data.weekStartDate").value("2026-08-17"));
    }

    @Test
    @DisplayName("주차 비활성화 재요청 → 멱등: 200 (오류 아님, 중복 행 없음)")
    void addWeekExceptionIdempotent() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");
        addException(id, "2026-08-17").andExpect(status().isCreated());

        addException(id, "2026-08-17").andExpect(status().isOk()); // 201 아님

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixed_schedule_week_exceptions WHERE fixed_schedule_id = ?",
                Integer.class, id);
        org.junit.jupiter.api.Assertions.assertEquals(1, count); // 중복 행 없음
    }

    @Test
    @DisplayName("이미 예외 행이 있어도 재삽입 → 200 (ON CONFLICT: rollback-only 500 회귀 방지)")
    void addWeekExceptionOnConflictNoRollbackError() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");
        // '다른 요청이 먼저 이긴' 상태를 흉내 — 유니크 충돌을 유발하는 행을 미리 심는다
        jdbc.update("""
                INSERT INTO fixed_schedule_week_exceptions (exception_id, fixed_schedule_id, week_start_date)
                VALUES (?, ?, ?)
                """, UUID.randomUUID(), id, java.time.LocalDate.of(2026, 8, 17));

        addException(id, "2026-08-17").andExpect(status().isOk()); // 500 아님 — ON CONFLICT DO NOTHING

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixed_schedule_week_exceptions WHERE fixed_schedule_id = ?",
                Integer.class, id);
        org.junit.jupiter.api.Assertions.assertEquals(1, count); // 중복 삽입 안 됨
    }

    @Test
    @DisplayName("없는 고정 일정에 주차 예외 → 404")
    void addWeekExceptionFixedNotFound() throws Exception {
        mockMvc.perform(post(PATH + "/" + UUID.randomUUID() + "/week-exceptions").header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weekStartDate":"2026-08-17"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 고정 일정에 주차 예외 → 404 (존재 은닉)")
    void addWeekExceptionOtherUserHidden() throws Exception {
        UUID id = create(OTHER, "남의수업", "MON", "09:00", "10:00");

        mockMvc.perform(post(PATH + "/" + id + "/week-exceptions").header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weekStartDate":"2026-08-17"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("weekStartDate 누락 → 400")
    void addWeekExceptionRequiresDate() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(post(PATH + "/" + id + "/week-exceptions").header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("주차 재활성화 → 204 · 예외 행 삭제됨")
    void removeWeekExceptionOk() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");
        addException(id, "2026-08-17").andExpect(status().isCreated());

        mockMvc.perform(delete(PATH + "/" + id + "/week-exceptions/2026-08-17").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixed_schedule_week_exceptions WHERE fixed_schedule_id = ?",
                Integer.class, id);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    @DisplayName("주차 재활성화 — 예외 없어도 204 (멱등)")
    void removeWeekExceptionIdempotent() throws Exception {
        UUID id = create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(delete(PATH + "/" + id + "/week-exceptions/2026-08-17").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("타인 고정 일정의 주차 예외 삭제 → 404 (존재 은닉)")
    void removeWeekExceptionOtherUserHidden() throws Exception {
        UUID id = create(OTHER, "남의수업", "MON", "09:00", "10:00");

        mockMvc.perform(delete(PATH + "/" + id + "/week-exceptions/2026-08-17").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- GET 목록 ----------

    @Test
    @DisplayName("목록 → weekday·시작 시각 순 정렬")
    void listOrdered() throws Exception {
        create(MAIN, "회의", "WED", "09:00", "10:00");
        create(MAIN, "근무", "MON", "13:00", "14:00");
        create(MAIN, "수업", "MON", "09:00", "10:00");

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].title").value("수업"))   // MON 09:00
                .andExpect(jsonPath("$.data[1].title").value("근무"))   // MON 13:00
                .andExpect(jsonPath("$.data[2].title").value("회의"));  // WED 09:00
    }

    @Test
    @DisplayName("목록 status=ACTIVE 필터 — INACTIVE는 제외")
    void listStatusFilter() throws Exception {
        create(MAIN, "활성", "MON", "09:00", "10:00");
        UUID inactive = create(MAIN, "비활성", "TUE", "09:00", "10:00");
        jdbc.update("UPDATE fixed_schedules SET status = 'INACTIVE' WHERE fixed_schedule_id = ?", inactive);

        mockMvc.perform(get(PATH).param("status", "ACTIVE").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("활성"));
    }

    @Test
    @DisplayName("소유자 스코프 — 타인 고정 일정은 내게 안 보임(빈 목록)")
    void ownerScope() throws Exception {
        create(OTHER, "남의수업", "MON", "09:00", "10:00");

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- fixtures ----------

    private org.springframework.test.web.servlet.ResultActions addException(UUID fixedScheduleId, String weekStartDate)
            throws Exception {
        return mockMvc.perform(post(PATH + "/" + fixedScheduleId + "/week-exceptions")
                .header("X-Dev-User", MAIN.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"weekStartDate\":\"" + weekStartDate + "\"}"));
    }

    private UUID create(UUID userId, String title, String weekday, String start, String end) throws Exception {
        String body = mockMvc.perform(post(PATH).header("X-Dev-User", userId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"weekday\":\"" + weekday
                                + "\",\"startTime\":\"" + start + "\",\"endTime\":\"" + end + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.data.fixedScheduleId"));
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
