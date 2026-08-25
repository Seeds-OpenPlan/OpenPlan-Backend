package com.openplan.backend.notification.controller;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 알림 <b>지연 생성</b> 테스트 (ADR-0014 결정 ①·③ · ST-B1-12).
 *
 * <p>고정 시각 {@link FixedClockConfig#FIXED_TODAY} = 2026-07-15(수), 주 시작(월) = 07-13.
 *
 * <p>고정하는 것: 조회 진입이 판정을 돌린다 · <b>재판정 no-op</b>(중복 방지) · 유형별 카피 문구 ·
 * 0건이면 미생성 · <b>꺼진 유형은 판정 자체를 건너뛴다</b>(만들고 거르는 게 아니다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class NotificationGenerationApiTest {

    private static final UUID MAIN = UUID.fromString("eeee0013-0000-0000-0000-000000000001");
    private static final LocalDate TODAY = FixedClockConfig.FIXED_TODAY;   // 2026-07-15(수)
    private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 13); // 월

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        jdbc.update("DELETE FROM notifications WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM notification_settings WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM execution_logs WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM support_tickets WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id = ?)", MAIN);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM tasks WHERE project_id IN (SELECT project_id FROM projects WHERE user_id = ?)", MAIN);
        jdbc.update("DELETE FROM projects WHERE user_id = ?", MAIN);
    }

    @Test
    @DisplayName("판정 재료가 없으면 조회해도 알림이 생기지 않는다")
    void nothingGeneratedWithoutSources() throws Exception {
        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("마감 임박(D-3 이내) → 태스크당 1건 · 상대 일수가 아니라 절대 날짜(m/d)")
    void deadlineSoonUsesAbsoluteDate() throws Exception {
        UUID project = insertProject(MAIN);
        insertTask(project, "기말 보고서", TODAY.plusDays(2), "IN_PROGRESS"); // 07-17

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].notificationType").value("DEADLINE_SOON"))
                .andExpect(jsonPath("$.data[0].title").value("'기말 보고서' 마감이 7/17로 임박했습니다"));
    }

    @Test
    @DisplayName("마감 임박 routePath 는 태스크를 여는 주간 화면 경로다 (화면 코드가 아니다)")
    void deadlineSoonRoutePath() throws Exception {
        UUID project = insertProject(MAIN);
        UUID task = insertTask(project, "과제", TODAY, "UNASSIGNED");

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data[0].routePath").value("/weekly?task=" + task));
    }

    @Test
    @DisplayName("D-3 밖(4일 뒤) 마감은 알리지 않는다")
    void deadlineOutsideWindowIsIgnored() throws Exception {
        UUID project = insertProject(MAIN);
        insertTask(project, "여유 있는 과제", TODAY.plusDays(4), "UNASSIGNED");

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("완료된 태스크는 마감이 임박해도 알리지 않는다")
    void completedTaskIsIgnored() throws Exception {
        UUID project = insertProject(MAIN);
        insertTask(project, "끝난 과제", TODAY, "COMPLETED");

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("재조회해도 같은 알림이 또 생기지 않는다 — 재판정은 no-op")
    void regenerationIsNoOp() throws Exception {
        UUID project = insertProject(MAIN);
        insertTask(project, "과제", TODAY.plusDays(1), "UNASSIGNED");

        mockMvc.perform(as(get("/api/v1/notifications"))).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(as(get("/api/v1/notifications"))).andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(as(get("/api/v1/notifications"))).andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("미저장 계획(DRAFT) → PLAN_UNSAVED. 계획 행이 없는 주는 미발생")
    void planUnsaved() throws Exception {
        insertWeeklyPlan(MAIN, WEEK_START, "DRAFT");

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data[0].notificationType").value("PLAN_UNSAVED"))
                .andExpect(jsonPath("$.data[0].title").value("이번 주 계획이 저장되지 않았습니다"))
                .andExpect(jsonPath("$.data[0].routePath").value("/weekly"));
    }

    @Test
    @DisplayName("확정된 계획(CONFIRMED)은 미저장 알림을 만들지 않는다")
    void confirmedPlanIsNotUnsaved() throws Exception {
        insertWeeklyPlan(MAIN, WEEK_START, "CONFIRMED");

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("지난주 수행 기록 → RETROSPECT. 권유가 아니라 서술이고 건수를 싣는다")
    void retrospectIsDescriptiveWithCount() throws Exception {
        UUID project = insertProject(MAIN);
        UUID task = insertTask(project, "과제", null, "IN_PROGRESS");
        insertExecutionLog(MAIN, task, "2026-07-08T01:00:00Z"); // 지난주(07-06~07-12)
        insertExecutionLog(MAIN, task, "2026-07-09T01:00:00Z");

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data[0].notificationType").value("RETROSPECT"))
                .andExpect(jsonPath("$.data[0].title").value("지난주 수행 기록이 2건 있습니다"))
                .andExpect(jsonPath("$.data[0].routePath").value("/statistics"));
    }

    @Test
    @DisplayName("지난주 기록이 없으면 회고 알림을 만들지 않는다 — 빈 통계로 보내지 않는다")
    void noRetrospectWithoutLogs() throws Exception {
        UUID project = insertProject(MAIN);
        UUID task = insertTask(project, "과제", null, "IN_PROGRESS");
        insertExecutionLog(MAIN, task, "2026-07-14T01:00:00Z"); // 이번 주 — 지난주가 아니다

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("답변 등록된 문의 → SUPPORT_ANSWERED, 본인 문의 상세로 이동")
    void supportAnswered() throws Exception {
        UUID ticket = insertAnsweredTicket(MAIN, "로그인이 안 됩니다");

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data[0].notificationType").value("SUPPORT_ANSWERED"))
                .andExpect(jsonPath("$.data[0].title").value("'로그인이 안 됩니다' 문의에 답변이 등록되었습니다"))
                .andExpect(jsonPath("$.data[0].routePath").value("/help/" + ticket));
    }

    @Test
    @DisplayName("20자 초과 제목은 잘리고 말줄임이 붙는다")
    void longTitleIsEllipsized() throws Exception {
        insertAnsweredTicket(MAIN, "가나다라마바사아자차카타파하가나다라마바사아자차"); // 24자

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data[0].title").value("'가나다라마바사아자차카타파하가나다라마바…' 문의에 답변이 등록되었습니다"));
    }

    @Test
    @DisplayName("🔴 꺼진 유형은 판정 자체를 건너뛴다 — 만들고 거르는 것이 아니라 행이 안 생긴다")
    void disabledTypeIsNotGeneratedAtAll() throws Exception {
        UUID project = insertProject(MAIN);
        insertTask(project, "과제", TODAY, "UNASSIGNED");

        mockMvc.perform(as(put("/api/v1/users/me/notification-settings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"settings\":[{\"notificationType\":\"DEADLINE_SOON\",\"isEnabled\":false}]}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(get("/api/v1/notifications")))
                .andExpect(jsonPath("$.data.length()").value(0));

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE user_id = ?", Integer.class, MAIN);
        org.junit.jupiter.api.Assertions.assertEquals(0, rows); // 필터가 아니라 미생성
    }

    // ---------------------------------------------------------------- 픽스처

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b) {
        return b.header("X-Dev-User", MAIN.toString());
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

    private UUID insertProject(UUID userId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, '프로젝트', NULL, NULL, 'IN_PROGRESS', NULL, NULL, 0, ?)
                """, id, userId, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title, LocalDate dueDate, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, 30, NULL, ?, ?, 0, ?)
                """, id, projectId, title, dueDate, status, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertWeeklyPlan(UUID userId, LocalDate weekStart, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, ?, NULL, 0, ?)
                """, id, userId, weekStart, weekStart.plusDays(6), status, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private void insertExecutionLog(UUID userId, UUID taskId, String startedAt) {
        jdbc.update("""
                INSERT INTO execution_logs (execution_log_id, user_id, task_id, plan_block_id,
                                            started_at, ended_at, actual_minutes, result, memo, created_at)
                VALUES (?, ?, ?, NULL, ?, NULL, 30, 'COMPLETED', NULL, ?)
                """, UUID.randomUUID(), userId, taskId,
                OffsetDateTime.parse(startedAt), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private UUID insertAnsweredTicket(UUID userId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO support_tickets (support_ticket_id, user_id, category, title, content,
                                             status, answer_content, answered_at, created_at)
                VALUES (?, ?, 'ETC', ?, '본문', 'ANSWERED', '답변', ?, ?)
                """, id, userId, title,
                OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }
}
