package com.openplan.backend.executionlog.controller;

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

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수행 이력 기록 API 통합 테스트 (PLAN-15 · DASH-05).
 *
 * <p>고정하는 것: 정상 201·응답 shape · 소유 체인 404 은닉(부재·타인) · 값 검증 422(시각 역전·5분 배수·
 * 결과 3값·필수 누락) · <b>타인 계획 블록 참조 차단</b> · 종료된 프로젝트 태스크 허용.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ExecutionLogApiTest {

    private static final String PATH = "/api/v1/tasks";
    private static final UUID MAIN = UUID.fromString("eeee0001-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("eeee0001-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID myTask;
    private UUID othersTask;
    private UUID closedProjectTask;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM execution_logs WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);

        myTask = insertTask(insertProject(MAIN, "내 프로젝트", "IN_PROGRESS"), "내 태스크");
        othersTask = insertTask(insertProject(OTHER, "타인 프로젝트", "IN_PROGRESS"), "타인 태스크");
        closedProjectTask = insertTask(insertProject(MAIN, "종료된 프로젝트", "CLOSED"), "종료 프로젝트 태스크");
    }

    @Test
    @DisplayName("기록 → 201 · 응답 shape(executionLogId·taskId·시각·분·결과·memo)")
    void recordOk() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T10:00:00Z",
                         "actualMinutes":60,"result":"COMPLETED","memo":"집중 잘 됨"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.executionLogId").isNotEmpty())
                .andExpect(jsonPath("$.data.taskId").value(myTask.toString()))
                .andExpect(jsonPath("$.data.actualMinutes").value(60))
                .andExpect(jsonPath("$.data.result").value("COMPLETED"))
                .andExpect(jsonPath("$.data.memo").value("집중 잘 됨"));
    }

    @Test
    @DisplayName("memo 없이도 기록 → 201 · memo=null")
    void recordWithoutMemo() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T09:30:00Z",
                         "actualMinutes":30,"result":"DELAYED"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.result").value("DELAYED"))
                .andExpect(jsonPath("$.data.memo").doesNotExist());
    }

    @Test
    @DisplayName("종료(CLOSED) 프로젝트 태스크도 기록 가능 → 201 (이미 일어난 일의 기록)")
    void closedProjectAllowed() throws Exception {
        mockMvc.perform(exec(closedProjectTask, """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T09:05:00Z",
                         "actualMinutes":5,"result":"COMPLETED"}"""))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("없는 태스크 → 404 E-COM-004")
    void taskNotFound() throws Exception {
        mockMvc.perform(exec(UUID.randomUUID(), """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T10:00:00Z",
                         "actualMinutes":60,"result":"COMPLETED"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 태스크 → 404 (존재 은닉, 남의 이력에 안 꽂힘)")
    void othersTaskHidden() throws Exception {
        mockMvc.perform(exec(othersTask, """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T10:00:00Z",
                         "actualMinutes":60,"result":"COMPLETED"}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_logs WHERE task_id = ?", Integer.class, othersTask);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    @DisplayName("종료 시각이 시작보다 빠르면 → 422 (endedAt.range)")
    void endedBeforeStarted() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"startedAt":"2026-07-02T10:00:00Z","endedAt":"2026-07-02T09:00:00Z",
                         "actualMinutes":60,"result":"COMPLETED"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("endedAt"))
                .andExpect(jsonPath("$.error.details.fields[0].rule").value("range"));
    }

    @Test
    @DisplayName("5분 단위 아님 → 422 (actualMinutes.step)")
    void notFiveMinuteStep() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T10:00:00Z",
                         "actualMinutes":63,"result":"COMPLETED"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("actualMinutes"))
                .andExpect(jsonPath("$.error.details.fields[0].rule").value("step"));
    }

    @Test
    @DisplayName("실제 시간 0 → 422 (양수여야 함)")
    void zeroMinutes() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T10:00:00Z",
                         "actualMinutes":0,"result":"COMPLETED"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("actualMinutes"));
    }

    @Test
    @DisplayName("미정의 결과값 → 422 (result.invalid, 500 아님)")
    void invalidResult() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"startedAt":"2026-07-02T09:00:00Z","endedAt":"2026-07-02T10:00:00Z",
                         "actualMinutes":60,"result":"DONE"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("result"))
                .andExpect(jsonPath("$.error.details.fields[0].rule").value("invalid"));
    }

    @Test
    @DisplayName("시작 시각 누락 → 422 (startedAt.required)")
    void startedAtRequired() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"endedAt":"2026-07-02T10:00:00Z","actualMinutes":60,"result":"COMPLETED"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("startedAt"))
                .andExpect(jsonPath("$.error.details.fields[0].rule").value("required"));
    }

    @Test
    @DisplayName("내 계획 블록 참조 → 201")
    void withOwnPlanBlock() throws Exception {
        UUID myBlock = insertPlanBlock(MAIN, myTask);
        mockMvc.perform(exec(myTask, """
                        {"planBlockId":"%s","startedAt":"2026-07-02T09:00:00Z",
                         "endedAt":"2026-07-02T10:00:00Z","actualMinutes":60,"result":"COMPLETED"}"""
                        .formatted(myBlock)))
                .andExpect(status().isCreated());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_logs WHERE plan_block_id = ?", Integer.class, myBlock);
        org.junit.jupiter.api.Assertions.assertEquals(1, count);
    }

    @Test
    @DisplayName("🔴 타인 계획 블록 참조 → 422 (내 이력에 남의 블록을 못 붙인다)")
    void othersPlanBlockRejected() throws Exception {
        UUID othersBlock = insertPlanBlock(OTHER, othersTask);
        mockMvc.perform(exec(myTask, """
                        {"planBlockId":"%s","startedAt":"2026-07-02T09:00:00Z",
                         "endedAt":"2026-07-02T10:00:00Z","actualMinutes":60,"result":"COMPLETED"}"""
                        .formatted(othersBlock)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("planBlockId"));

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution_logs WHERE user_id = ?", Integer.class, MAIN);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    @DisplayName("없는 계획 블록 참조 → 422 (부재·타인 구분 안 함)")
    void unknownPlanBlockRejected() throws Exception {
        mockMvc.perform(exec(myTask, """
                        {"planBlockId":"%s","startedAt":"2026-07-02T09:00:00Z",
                         "endedAt":"2026-07-02T10:00:00Z","actualMinutes":60,"result":"COMPLETED"}"""
                        .formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("planBlockId"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder exec(
            UUID taskId, String body) {
        return post(PATH + "/" + taskId + "/execution-logs")
                .header("X-Dev-User", MAIN.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
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

    private UUID insertProject(UUID userId, String name, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, NULL, ?, NULL, NULL, 0, ?)
                """, id, userId, name, status, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, 'UNASSIGNED', 0, ?)
                """, id, projectId, title, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertPlanBlock(UUID userId, UUID taskId) {
        UUID planId = UUID.randomUUID();
        LocalDate weekStart = LocalDate.of(2026, 6, 29);
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, 'DRAFT', NULL, 0, ?)
                """, planId, userId, weekStart, weekStart.plusDays(6),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));

        UUID blockId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO plan_blocks (plan_block_id, weekly_plan_id, task_id, schedule_id,
                                         block_type, start_at, end_at, status, created_at)
                VALUES (?, ?, ?, NULL, 'TASK', ?, ?, 'SCHEDULED', ?)
                """, blockId, planId, taskId,
                OffsetDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE.plusSeconds(7200), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return blockId;
    }
}
