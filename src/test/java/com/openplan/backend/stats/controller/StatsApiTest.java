package com.openplan.backend.stats.controller;

import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수행 통계 3종 통합 테스트 (ST-B2-16). {@link FixedClockConfig#FIXED_TODAY} = 2026-07-15(수) ·
 * 주(월요일 시작) = 07-13(월)~07-19(일) · zone=Asia/Seoul.
 *
 * <p>고정하는 것: 이력 0건 빈 상태(200, empty=true) · period 누락 400 · period 값 오류 422 ·
 * 편차 그룹핑(카테고리 미지정 "없음") · 구간 분류(DAWN/AFTERNOON 경계).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class StatsApiTest {

    private static final String PATH = "/api/v1/stats";
    private static final UUID MAIN = UUID.fromString("eeee0002-0000-0000-0000-000000000001");
    // 2026-07-15T00:00:00Z(UTC) = Asia/Seoul 2026-07-15 09:00 — 이 주(07-13~07-19) 내부 값들.
    private static final Instant MON_MORNING = Instant.parse("2026-07-13T01:00:00Z");   // KST 10:00 → MORNING
    private static final Instant WED_DAWN = Instant.parse("2026-07-14T20:00:00Z");      // KST 07-15 05:00 → DAWN

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID project;
    private UUID category;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        jdbc.update("DELETE FROM execution_logs WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM tasks WHERE project_id IN (SELECT project_id FROM projects WHERE user_id = ?)", MAIN);
        jdbc.update("DELETE FROM task_categories WHERE user_id = ?", MAIN);
        jdbc.update("DELETE FROM projects WHERE user_id = ?", MAIN);

        project = insertProject(MAIN, "테스트 프로젝트");
        category = insertCategory(MAIN, "과제");
    }

    @Test
    @DisplayName("이력 0건 → 200 · empty=true (오류 아님, RB-STAT-01 GWT)")
    void summariesEmpty() throws Exception {
        mockMvc.perform(statsGet("/summaries", "period=WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.empty").value(true))
                .andExpect(jsonPath("$.data.totalEstimatedMinutes").value(0))
                .andExpect(jsonPath("$.data.completionRate").doesNotExist());
    }

    @Test
    @DisplayName("period 누락 → 400 E-COM-001")
    void summariesMissingPeriod() throws Exception {
        mockMvc.perform(statsGet("/summaries", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("period 미정의값 → 422 E-COM-009 (500 아님)")
    void summariesInvalidPeriod() throws Exception {
        mockMvc.perform(statsGet("/summaries", "period=YEARLY"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("period"));
    }

    @Test
    @DisplayName("이력 1건 → totalEstimated/Actual·completionRate 반영")
    void summariesAggregates() throws Exception {
        UUID task = insertTask(project, category, 60);
        insertExecutionLog(task, MON_MORNING, 90, "COMPLETED");

        mockMvc.perform(statsGet("/summaries", "period=WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.empty").value(false))
                .andExpect(jsonPath("$.data.totalEstimatedMinutes").value(60))
                .andExpect(jsonPath("$.data.totalActualMinutes").value(90))
                .andExpect(jsonPath("$.data.completionRate").value(100.0))
                .andExpect(jsonPath("$.data.varianceRate").value(50.0)); // (90-60)/60*100
    }

    @Test
    @DisplayName("카테고리 미지정 태스크는 groupId=null·groupName='없음'으로 그룹핑된다")
    void deviationsUncategorizedGroup() throws Exception {
        UUID task = insertTask(project, null, 40);
        insertExecutionLog(task, MON_MORNING, 40, "COMPLETED");

        mockMvc.perform(statsGet("/deviations", "period=WEEKLY&groupBy=CATEGORY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.empty").value(false))
                .andExpect(jsonPath("$.data.rows[0].groupId").isEmpty())
                .andExpect(jsonPath("$.data.rows[0].groupName").value("없음"))
                .andExpect(jsonPath("$.data.rows[0].deviationMinutes").value(0));
    }

    @Test
    @DisplayName("groupBy=PROJECT → 프로젝트명으로 그룹핑")
    void deviationsByProject() throws Exception {
        UUID task = insertTask(project, category, 30);
        insertExecutionLog(task, MON_MORNING, 45, "COMPLETED");

        mockMvc.perform(statsGet("/deviations", "period=WEEKLY&groupBy=PROJECT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].groupId").value(project.toString()))
                .andExpect(jsonPath("$.data.rows[0].groupName").value("테스트 프로젝트"))
                .andExpect(jsonPath("$.data.rows[0].deviationMinutes").value(15));
    }

    @Test
    @DisplayName("groupBy 누락 → 400")
    void deviationsMissingGroupBy() throws Exception {
        mockMvc.perform(statsGet("/deviations", "period=WEEKLY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("시간대 분류 — DAWN(00~06 이전)·MORNING 경계가 zone 기준으로 정확히 갈린다")
    void timePatternSlots() throws Exception {
        UUID task = insertTask(project, category, 30);
        insertExecutionLog(task, MON_MORNING, 30, "COMPLETED");   // KST 10:00 → MORNING
        insertExecutionLog(task, WED_DAWN, 30, "DELAYED");        // KST 05:00 → DAWN

        mockMvc.perform(statsGet("/time-patterns", "period=WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.empty").value(false))
                .andExpect(jsonPath("$.data.slots[0].slot").value("DAWN"))
                .andExpect(jsonPath("$.data.slots[0].totalCount").value(1))
                .andExpect(jsonPath("$.data.slots[0].completedCount").value(0))
                .andExpect(jsonPath("$.data.slots[1].slot").value("MORNING"))
                .andExpect(jsonPath("$.data.slots[1].totalCount").value(1))
                .andExpect(jsonPath("$.data.slots[1].completedCount").value(1));
    }

    @Test
    @DisplayName("time-patterns는 period 생략 가능(기본 WEEKLY) — 400 아님")
    void timePatternsPeriodOptional() throws Exception {
        mockMvc.perform(statsGet("/time-patterns", ""))
                .andExpect(status().isOk());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder statsGet(String path, String query) {
        MockHttpServletRequestBuilder req = get(PATH + path + (query.isBlank() ? "" : "?" + query));
        return req.header("X-Dev-User", MAIN.toString());
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

    private UUID insertProject(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, NULL, 'IN_PROGRESS', NULL, NULL, 0, ?)
                """, id, userId, name, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertCategory(UUID userId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO task_categories (task_category_id, user_id, name, sort_order, created_at)
                VALUES (?, ?, ?, 0, ?)
                """, id, userId, name, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, UUID categoryId, Integer estimatedMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, ?, '테스트 태스크', NULL, ?, NULL, NULL, 'IN_PROGRESS', 0, ?)
                """, id, projectId, categoryId, estimatedMinutes, OffsetDateTime.now(ZoneOffset.UTC));
        return id;
    }

    private void insertExecutionLog(UUID taskId, Instant startedAt, int actualMinutes, String result) {
        jdbc.update("""
                INSERT INTO execution_logs (execution_log_id, user_id, task_id, plan_block_id,
                                            started_at, ended_at, actual_minutes, result, memo, created_at)
                VALUES (?, ?, ?, NULL, ?, ?, ?, ?, NULL, ?)
                """, UUID.randomUUID(), MAIN, taskId,
                OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(startedAt.plusSeconds(actualMinutes * 60L), ZoneOffset.UTC),
                actualMinutes, result, OffsetDateTime.now(ZoneOffset.UTC));
    }
}
