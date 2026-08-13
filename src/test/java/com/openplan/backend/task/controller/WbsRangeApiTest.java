package com.openplan.backend.task.controller;

import com.openplan.backend.project.domain.ProjectStatus;
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
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WBS 기간 API 통합 테스트 (ST-B2-05 / PUT {@code /tasks/{taskId}/wbs-range}). 업서트(있으면 갱신,
 * 없으면 생성)·존재 은닉 404·D-10 CLOSED 가드·E-WBS-001 경계값을 고정한다. version 없음(설계상 —
 * {@code WbsItem} 클래스 상단 참고)이라 409 케이스는 없다. FixedClockConfig(FIXED_TODAY=2026-07-15)로
 * 평가 결정성 확보.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class WbsRangeApiTest {

    private static final UUID MAIN = UUID.fromString("ffffffff-0000-0000-0000-000000000003");
    private static final UUID OTHER = UUID.fromString("ffffffff-0000-0000-0000-000000000004");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID inProgress;
    private UUID closed;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM wbs_items WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);

        inProgress = insertProject(MAIN, "진행중", ProjectStatus.IN_PROGRESS, null);
        closed = insertProject(MAIN, "종료", ProjectStatus.CLOSED, BASE);
    }

    @Test
    @DisplayName("정상 — 신규 생성 200, taskTitle 동봉")
    void createOk() throws Exception {
        UUID id = insertTask(inProgress, "기초 공사");

        put(id, "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-10\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(id.toString()))
                .andExpect(jsonPath("$.data.taskTitle").value("기초 공사"))
                .andExpect(jsonPath("$.data.startDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data.endDate").value("2026-08-10"))
                .andExpect(jsonPath("$.data.wbsItemId").exists());
    }

    @Test
    @DisplayName("정상 — 업서트: 재호출은 기존 행을 갱신(행 1개 유지, 값만 교체)")
    void upsertUpdatesExistingRow() throws Exception {
        UUID id = insertTask(inProgress, "기초 공사");

        put(id, "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-10\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value("2026-08-01"));

        put(id, "{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-05\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.data.endDate").value("2026-09-05"));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM wbs_items WHERE task_id = ?", Integer.class, id);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1); // 행 1개(갱신이지 추가 아님)
    }

    @Test
    @DisplayName("경계값 — startDate == endDate 는 허용(ck_wbs_range: start_date <= end_date)")
    void equalDatesAllowed() throws Exception {
        UUID id = insertTask(inProgress, "당일 작업");

        put(id, "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-01\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startDate").value("2026-08-01"))
                .andExpect(jsonPath("$.data.endDate").value("2026-08-01"));
    }

    @Test
    @DisplayName("E-WBS-001 — endDate < startDate → 422")
    void endBeforeStartRejected() throws Exception {
        UUID id = insertTask(inProgress, "역전 케이스");

        put(id, "{\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-01\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-WBS-001"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("endDate"));
    }

    @Test
    @DisplayName("400 — startDate/endDate 누락")
    void missingFieldsBadRequest() throws Exception {
        UUID id = insertTask(inProgress, "누락 케이스");

        put(id, "{\"endDate\":\"2026-08-10\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
        put(id, "{\"startDate\":\"2026-08-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("404 — 부재 taskId")
    void notFoundMissingTask() throws Exception {
        put(UUID.randomUUID(), "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-10\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("404 — 타인 taskId (존재 은닉)")
    void notFoundOthersTask() throws Exception {
        UUID othersProject = insertProject(OTHER, "타인프로젝트", ProjectStatus.IN_PROGRESS, null);
        UUID othersTask = insertTask(othersProject, "타인 태스크");

        put(othersTask, "{\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-10\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("422 E-PROJ-005 — CLOSED 프로젝트 소속 태스크 (값 검증보다 먼저)")
    void closedProjectRejected() throws Exception {
        UUID id = insertTask(closed, "종료프로젝트 태스크");

        // endDate<startDate(E-WBS-001 유발 조건)를 같이 줘도 CLOSED가 먼저 걸린다.
        put(id, "{\"startDate\":\"2026-08-10\",\"endDate\":\"2026-08-01\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("project.status"));
    }

    // ---------- fixtures ----------

    private ResultActions put(UUID taskId, String body) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/tasks/" + taskId + "/wbs-range")
                .header("X-Dev-User", MAIN.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    private UUID insertProject(UUID userId, String name, ProjectStatus status, Instant closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, NULL, ?, NULL, ?, 0, ?)
                """,
                id, userId, name, status.name(),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, 0, ?)
                """,
                id, projectId, title, TaskStatus.UNASSIGNED.name(), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
