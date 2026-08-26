package com.openplan.backend.weeklyplan.controller;

import com.jayway.jsonpath.JsonPath;
import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import com.openplan.backend.task.domain.TaskStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 블록 일괄 적용 API 통합 테스트 (RB-PLAN-01·PLAN-29) — {@code POST /weekly-plans/{planId}/block-batches}.
 * CREATE/MOVE/DELETE 혼합 실행·원자성(실패 시 전체 롤백)·WeeklyPlanView 반환·404/422를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class PlanBlockBatchApiTest {

    private static final UUID MAIN = UUID.fromString("aaaa2222-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("aaaa2222-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate WEEK = LocalDate.of(2026, 8, 3);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID project;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM plan_blocks WHERE weekly_plan_id IN "
                + "(SELECT weekly_plan_id FROM weekly_plans WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM weekly_plans WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
        project = insertProject(MAIN, "프로젝트");
    }

    @Test
    @DisplayName("CREATE 2건 일괄 → 200 · 블록 2개 생성 · WeeklyPlanView(data.plan·blocks) 반환 · total=240")
    void batchCreate() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID task1 = insertTask(project, "태스크1", TaskStatus.UNASSIGNED);
        UUID task2 = insertTask(project, "태스크2", TaskStatus.UNASSIGNED);

        String body = """
                {"operations":[
                  {"op":"CREATE","block":{"blockType":"TASK","taskId":"%s",
                     "startAt":"2026-08-03T00:00:00Z","endAt":"2026-08-03T03:00:00Z"}},
                  {"op":"CREATE","block":{"blockType":"TASK","taskId":"%s",
                     "startAt":"2026-08-03T04:00:00Z","endAt":"2026-08-03T05:00:00Z"}}
                ]}""".formatted(task1, task2);

        batch(MAIN, plan, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.weeklyPlanId").value(plan.toString()))
                .andExpect(jsonPath("$.data.plan.placedBlockCount").value(2))
                .andExpect(jsonPath("$.data.blocks.length()").value(2));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(240); // 180 + 60
    }

    @Test
    @DisplayName("CREATE·MOVE·DELETE 혼합 → 200 · 최종 상태 정확")
    void batchMixed() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID task1 = insertTask(project, "유지태스크", TaskStatus.UNASSIGNED);
        UUID task2 = insertTask(project, "삭제태스크", TaskStatus.UNASSIGNED);
        UUID keepBlock = placeTaskBlock(plan, task1, "2026-08-03T00:00:00Z", "2026-08-03T01:00:00Z");   // 60
        UUID delBlock = placeTaskBlock(plan, task2, "2026-08-03T02:00:00Z", "2026-08-03T03:00:00Z");    // 60
        UUID task3 = insertTask(project, "신규태스크", TaskStatus.UNASSIGNED);

        String body = """
                {"operations":[
                  {"op":"CREATE","block":{"blockType":"TASK","taskId":"%s",
                     "startAt":"2026-08-03T05:00:00Z","endAt":"2026-08-03T06:00:00Z"}},
                  {"op":"MOVE","planBlockId":"%s","block":{"blockType":"TASK","taskId":"%s",
                     "startAt":"2026-08-03T00:00:00Z","endAt":"2026-08-03T02:00:00Z"}},
                  {"op":"DELETE","planBlockId":"%s"}
                ]}""".formatted(task3, keepBlock, task1, delBlock);

        batch(MAIN, plan, body).andExpect(status().isOk());

        // keepBlock(120) + 신규(60) = 180, delBlock 삭제됨
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE plan_block_id = ?",
                Integer.class, delBlock)).isZero();
        assertThat(jdbc.queryForObject("SELECT total_planned_minutes FROM weekly_plans WHERE weekly_plan_id = ?",
                Integer.class, plan)).isEqualTo(180);
        // 삭제 태스크는 마지막 블록 제거 → UNASSIGNED 복귀
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = ?", String.class, task2))
                .isEqualTo("UNASSIGNED");
    }

    @Test
    @DisplayName("원자성 — 중간 op 실패(잘못된 시각) 시 전체 롤백(앞선 CREATE도 반영 안 됨)")
    void batchAtomicRollback() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);

        String body = """
                {"operations":[
                  {"op":"CREATE","block":{"blockType":"TASK","taskId":"%s",
                     "startAt":"2026-08-03T00:00:00Z","endAt":"2026-08-03T01:00:00Z"}},
                  {"op":"CREATE","block":{"blockType":"TASK","taskId":"%s",
                     "startAt":"2026-08-03T03:00:00Z","endAt":"2026-08-03T02:00:00Z"}}
                ]}""".formatted(task, task); // 2번째 start>end → 422

        batch(MAIN, plan, body)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PLAN-002"));

        // 앞선 CREATE도 롤백 — 블록 0개
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE weekly_plan_id = ?",
                Integer.class, plan)).isZero();
    }

    @Test
    @DisplayName("CREATE block 필수 필드 누락 → 422 (컨트롤러 @Valid가 없는 경로라 서비스가 막아야 한다)")
    void batchCreateMissingRequiredFields() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);

        // blockType·startAt·endAt은 PlanBlockCreateRequest에 @NotNull이지만, 배치 경로는
        // Operation.block에 @Valid 캐스케이드가 없어 Bean Validation이 돌지 않는다.
        record Missing(String field, String blockJson) {
        }
        Missing[] cases = {
                new Missing("blockType", "{\"taskId\":\"" + task + "\","
                        + "\"startAt\":\"2026-08-03T00:00:00Z\",\"endAt\":\"2026-08-03T01:00:00Z\"}"),
                new Missing("startAt", "{\"blockType\":\"TASK\",\"taskId\":\"" + task + "\","
                        + "\"endAt\":\"2026-08-03T01:00:00Z\"}"),
                new Missing("endAt", "{\"blockType\":\"TASK\",\"taskId\":\"" + task + "\","
                        + "\"startAt\":\"2026-08-03T00:00:00Z\"}"),
        };

        for (Missing c : cases) {
            batch(MAIN, plan, "{\"operations\":[{\"op\":\"CREATE\",\"block\":" + c.blockJson() + "}]}")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                    .andExpect(jsonPath("$.error.details.fields[0].field").value(c.field()));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan_blocks WHERE weekly_plan_id = ?",
                Integer.class, plan)).isZero();
    }

    @Test
    @DisplayName("미정의 op → 422")
    void batchInvalidOp() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        batch(MAIN, plan, "{\"operations\":[{\"op\":\"FOO\",\"planBlockId\":\"" + UUID.randomUUID() + "\"}]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].field").value("op"));
    }

    @Test
    @DisplayName("operations 비어 있음 → 400")
    void batchEmptyOperations() throws Exception {
        UUID plan = insertWeeklyPlan(MAIN, WEEK);

        batch(MAIN, plan, "{\"operations\":[]}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("없는/타인 계획 → 404")
    void batchPlanNotFound() throws Exception {
        UUID task = insertTask(project, "태스크", TaskStatus.UNASSIGNED);
        String body = """
                {"operations":[{"op":"CREATE","block":{"blockType":"TASK","taskId":"%s",
                   "startAt":"2026-08-03T00:00:00Z","endAt":"2026-08-03T01:00:00Z"}}]}""".formatted(task);

        batch(MAIN, UUID.randomUUID(), body)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    // ---------- fixtures ----------

    private org.springframework.test.web.servlet.ResultActions batch(UUID userId, UUID planId, String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/weekly-plans/" + planId + "/block-batches")
                .header("X-Dev-User", userId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID placeTaskBlock(UUID plan, UUID task, String start, String end) throws Exception {
        String body = "{\"blockType\":\"TASK\",\"taskId\":\"" + task + "\","
                + "\"startAt\":\"" + start + "\",\"endAt\":\"" + end + "\"}";
        String json = mockMvc.perform(post("/api/v1/weekly-plans/" + plan + "/blocks")
                        .header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.data.planBlockId"));
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
                """, id, userId, name, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title, TaskStatus status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, 0, ?)
                """, id, projectId, title, status.name(), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertWeeklyPlan(UUID userId, LocalDate weekStart) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date,
                                          total_planned_minutes, status, confirmed_at, version, created_at)
                VALUES (?, ?, ?, ?, 0, 'DRAFT', NULL, 0, ?)
                """, id, userId, weekStart, weekStart.plusDays(6), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
