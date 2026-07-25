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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 미배치 태스크 조회 API 통합 테스트 (PROJ-19 / EP-7 · AC-U-1~4). 사용자 전체 스코프·프로젝트명 조인·
 * IN_PROGRESS 프로젝트만·status 전용값·평가 경유([T7] stale IN_PROGRESS 미포함)를 고정한다.
 * FixedClockConfig(FIXED_TODAY=2026-07-15).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskUnassignedApiTest {

    private static final String PATH = "/api/v1/tasks";
    private static final UUID MAIN = UUID.fromString("c3c3c3c3-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("c3c3c3c3-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------- AC-U-1 · AC-U-2 스코프·필터 ----------

    @Test
    @DisplayName("AC-U-1·U-2 전 프로젝트 UNASSIGNED만 + 프로젝트명 조인 · IN_PROGRESS 태스크·PAUSED/CLOSED 프로젝트 제외")
    void unassignedScopeAndFilter() throws Exception {
        UUID pA = insertProject(MAIN, "프로젝트A", ProjectStatus.IN_PROGRESS, null);
        UUID pB = insertProject(MAIN, "프로젝트B", ProjectStatus.IN_PROGRESS, null);
        UUID paused = insertProject(MAIN, "중지프로젝트", ProjectStatus.PAUSED, null);
        UUID closed = insertProject(MAIN, "종료프로젝트", ProjectStatus.CLOSED, null);

        insertTask(pA, "미배치A1", TaskStatus.UNASSIGNED, BASE.plusSeconds(10));   // 포함
        insertTask(pA, "진행중A2", TaskStatus.IN_PROGRESS, BASE.plusSeconds(20));  // 제외(UNASSIGNED 아님)
        insertTask(pB, "미배치B1", TaskStatus.UNASSIGNED, BASE.plusSeconds(30));   // 포함
        insertTask(paused, "중지-미배치", TaskStatus.UNASSIGNED, BASE.plusSeconds(40)); // 제외(PAUSED)
        insertTask(closed, "종료-미배치", TaskStatus.UNASSIGNED, BASE.plusSeconds(50)); // 제외(CLOSED)
        // 타인 것
        UUID pOther = insertProject(OTHER, "타인", ProjectStatus.IN_PROGRESS, null);
        insertTask(pOther, "타인-미배치", TaskStatus.UNASSIGNED, BASE.plusSeconds(60)); // 제외(타인)

        mockMvc.perform(get(PATH).param("status", "UNASSIGNED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.meta.page.totalElements").value(2))
                // 정렬 created_at DESC → B1(30s) 먼저, A1(10s) 뒤
                .andExpect(jsonPath("$.data[0].title").value("미배치B1"))
                .andExpect(jsonPath("$.data[0].projectName").value("프로젝트B"))
                .andExpect(jsonPath("$.data[0].status").value("UNASSIGNED"))
                .andExpect(jsonPath("$.data[1].title").value("미배치A1"))
                .andExpect(jsonPath("$.data[1].projectName").value("프로젝트A"));
    }

    // ---------- [T7] 평가 경유 — stale IN_PROGRESS 미포함 ----------

    @Test
    @DisplayName("[T7] TB-5: 기한 경과 stale IN_PROGRESS 프로젝트의 미배치 태스크 → 평가가 CLOSED 선반영 → 미포함")
    void staleInProgressExcludedViaEvaluation() throws Exception {
        // due_date 2026-07-01 < FIXED_TODAY(2026-07-15), status는 아직 IN_PROGRESS (stale)
        UUID stale = insertProject(MAIN, "기한경과", ProjectStatus.IN_PROGRESS, LocalDate.of(2026, 7, 1));
        insertTask(stale, "경과-미배치", TaskStatus.UNASSIGNED, BASE.plusSeconds(10));

        // 조회 시 closeOverdue 선행 → stale 프로젝트가 CLOSED로 전환 → 미포함
        mockMvc.perform(get(PATH).param("status", "UNASSIGNED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.page.totalElements").value(0));

        // 평가가 실제 영속됐는지(CLOSED 전환) 확인
        String s = jdbc.queryForObject("SELECT status FROM projects WHERE project_id = ?", String.class, stale);
        org.assertj.core.api.Assertions.assertThat(s).isEqualTo("CLOSED");
    }

    // ---------- AC-U-3 status: 생략=UNASSIGNED 기본값, 타 값만 422 ----------

    @Test
    @DisplayName("AC-U-3 status 생략 → UNASSIGNED 기본값으로 조회 성공 · UNASSIGNED 외 값 → 422 E-COM-009")
    void statusDefaultsToUnassigned() throws Exception {
        UUID p = insertProject(MAIN, "P", ProjectStatus.IN_PROGRESS, null);
        insertTask(p, "미배치", TaskStatus.UNASSIGNED, BASE.plusSeconds(10));
        insertTask(p, "진행중", TaskStatus.IN_PROGRESS, BASE.plusSeconds(20)); // 제외

        // status 생략 → 기본값 UNASSIGNED로 조회 (200, 미배치 1건)
        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("미배치"));

        // UNASSIGNED 외 값은 여전히 거부
        mockMvc.perform(get(PATH).param("status", "COMPLETED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    // ---------- AC-U-4 빈 목록·페이지 ----------

    @Test
    @DisplayName("AC-U-4 0건 → 빈 목록 성공 · page/size 위반 → 400")
    void emptyAndPagination() throws Exception {
        mockMvc.perform(get(PATH).param("status", "UNASSIGNED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta.page.totalElements").value(0));

        mockMvc.perform(get(PATH).param("status", "UNASSIGNED").param("page", "0")
                        .header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
        mockMvc.perform(get(PATH).param("status", "UNASSIGNED").param("size", "101")
                        .header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC-U-4 size=1 페이지네이션 — totalPages 반영")
    void paging() throws Exception {
        UUID p = insertProject(MAIN, "P", ProjectStatus.IN_PROGRESS, null);
        insertTask(p, "U1", TaskStatus.UNASSIGNED, BASE.plusSeconds(10));
        insertTask(p, "U2", TaskStatus.UNASSIGNED, BASE.plusSeconds(20));

        mockMvc.perform(get(PATH).param("status", "UNASSIGNED").param("page", "1").param("size", "1")
                        .header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("U2")) // 최신 먼저
                .andExpect(jsonPath("$.meta.page.totalElements").value(2))
                .andExpect(jsonPath("$.meta.page.totalPages").value(2));
    }

    // ---------- fixtures ----------

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

    private UUID insertProject(UUID userId, String name, ProjectStatus status, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, ?, ?, NULL, ?, 0, ?)
                """,
                id, userId, name, dueDate, status.name(),
                status == ProjectStatus.CLOSED ? OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC) : null,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertTask(UUID projectId, String title, TaskStatus status, Instant createdAt) {
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, NULL, NULL, NULL, ?, 0, ?)
                """,
                UUID.randomUUID(), projectId, title, status.name(),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }
}
