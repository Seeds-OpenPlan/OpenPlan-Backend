package com.openplan.backend.structuring.controller;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 구조 부족 경고 API 통합 테스트 (SS-04 / RB-PROJ-02) — {@code GET /projects/{projectId}/structure-warnings}.
 *
 * <p>단위 테스트({@code StructureWarningPolicyTest})가 판정 자체를 고정하므로 여기서는 <b>배선</b>을 본다:
 * 카운트 3종의 스코프가 서비스에서 제대로 이어졌는지([G5] — 이 스토리 최대 혼동 지점), 자동 종료 평가가
 * 선행하는지([G3]), 상태 분기가 API 응답으로 확인되는지([G6][G9]), 소유 격리([G7]).
 *
 * <p>고정 시계 {@code FIXED_TODAY = 2026-07-15}(Asia/Seoul) 기준으로 마감 경계를 만든다.
 * count 단언이 있어 전용 사용자로 시딩한다([C5] — 자동 종료의 REQUIRES_NEW 커밋이 남기는 오염 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class StructureWarningApiTest {

    private static final UUID MAIN = UUID.fromString("bbbb3333-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbb3333-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");
    private static final LocalDate TODAY = FixedClockConfig.FIXED_TODAY; // 2026-07-15

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

    // ═══════════════ 봉투·기본 ═══════════════

    @Test
    @DisplayName("경고 없음 → 200 · 빈 배열 (404·422 아님) · meta 없음")
    void noWarningsReturnsEmptyArray() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", TODAY.plusDays(30));
        insertTask(project, "A", TaskStatus.UNASSIGNED, 60);
        insertTask(project, "B", TaskStatus.UNASSIGNED, 60);
        insertTask(project, "C", TaskStatus.UNASSIGNED, 60);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.meta").doesNotExist())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("[G8] 빈 프로젝트 → TOO_FEW 정확히 1건 (API 왕복)")
    void emptyProjectReturnsSingleTooFew() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", null);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].warningType").value("TOO_FEW_TASKS"))
                .andExpect(jsonPath("$.data[0].reason").value("태스크가 0건입니다. (기준 3건 미만)"))
                .andExpect(jsonPath("$.data[0].action").value("ADD_TASK"));
    }

    // ═══════════════ [G5] 혼합 상태 스코프 배선 ═══════════════

    @Test
    @DisplayName("[G5] COMPLETED의 예상시간 null은 미산입 — 미완료가 전부 입력돼 있으면 MISSING 미발생")
    void completedNullEstimateNotCounted() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", null);
        insertTask(project, "미완료1", TaskStatus.UNASSIGNED, 60);
        insertTask(project, "미완료2", TaskStatus.IN_PROGRESS, 60);
        insertTask(project, "미완료3", TaskStatus.UNASSIGNED, 60);
        insertTask(project, "완료-예상시간없음", TaskStatus.COMPLETED, null); // 세면 안 되는 행

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0)); // total=4라 TOO_FEW도 미발생
    }

    @Test
    @DisplayName("[G5] 혼합 상태에서 MISSING 건수가 정확 — 미완료 2건만 세고 COMPLETED 2건은 제외")
    void missingCountsOnlyRemaining() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", null);
        insertTask(project, "미완료-null1", TaskStatus.UNASSIGNED, null);
        insertTask(project, "미완료-null2", TaskStatus.IN_PROGRESS, null);
        insertTask(project, "완료-입력됨1", TaskStatus.COMPLETED, 60);
        insertTask(project, "완료-입력됨2", TaskStatus.COMPLETED, 90);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)) // total=4라 TOO_FEW 미발생
                .andExpect(jsonPath("$.data[0].warningType").value("MISSING_ESTIMATES"))
                .andExpect(jsonPath("$.data[0].reason")
                        .value("예상시간이 비어 있는 미완료 태스크가 2건 있습니다."));
    }

    @Test
    @DisplayName("총량 2건·전량 COMPLETED → TOO_FEW만 (total은 상태 무관, 나머지는 미완료 스코프)")
    void allCompletedYieldsOnlyTooFew() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", TODAY.plusDays(1));
        insertTask(project, "완료1", TaskStatus.COMPLETED, null);
        insertTask(project, "완료2", TaskStatus.COMPLETED, null);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].warningType").value("TOO_FEW_TASKS"));
    }

    // ═══════════════ [G4] 다중 발생 순서 ═══════════════

    @Test
    @DisplayName("[G4] 3종 동시 → 3건 · 순서·문구 전체 골든")
    void allThreeWarningsInOrder() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", TODAY.plusDays(2));
        insertTask(project, "A", TaskStatus.UNASSIGNED, null);
        insertTask(project, "B", TaskStatus.UNASSIGNED, null);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].warningType").value("TOO_FEW_TASKS"))
                .andExpect(jsonPath("$.data[0].reason").value("태스크가 2건입니다. (기준 3건 미만)"))
                .andExpect(jsonPath("$.data[0].action").value("ADD_TASK"))
                .andExpect(jsonPath("$.data[1].warningType").value("MISSING_ESTIMATES"))
                .andExpect(jsonPath("$.data[1].reason")
                        .value("예상시간이 비어 있는 미완료 태스크가 2건 있습니다."))
                .andExpect(jsonPath("$.data[1].action").value("EDIT_TASK"))
                .andExpect(jsonPath("$.data[2].warningType").value("DEADLINE_PRESSURE"))
                .andExpect(jsonPath("$.data[2].reason")
                        .value("마감까지 2일, 미완료 태스크가 2건 남았습니다."))
                .andExpect(jsonPath("$.data[2].action").value("EDIT_TASK"));
    }

    // ═══════════════ [G3] 자동 종료 평가 선행 ═══════════════

    @Test
    @DisplayName("[G3] 어제 마감 stale IN_PROGRESS → 평가 선행으로 CLOSED 전환 → 빈 배열 (+ DB 영속 확인)")
    void staleInProgressIsClosedByEvaluationFirst() throws Exception {
        // 태스크 0건이라 평가가 없으면 TOO_FEW가 나왔을 상태
        UUID project = insertProject(MAIN, "IN_PROGRESS", TODAY.minusDays(1));

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        assertThat(jdbc.queryForObject("SELECT status FROM projects WHERE project_id = ?",
                String.class, project)).isEqualTo("CLOSED"); // 평가가 실제로 커밋됐다
    }

    // ═══════════════ [G6][G9] 상태 분기 ═══════════════

    @Test
    @DisplayName("[G6] PAUSED + 과거 마감 → TOO_FEW·MISSING 2건 발생 · DEADLINE 부재(음수 일 문구 없음)")
    void pausedJudgesTwoAndSuppressesDeadline() throws Exception {
        UUID project = insertProject(MAIN, "PAUSED", TODAY.minusDays(10));
        insertTask(project, "완료", TaskStatus.COMPLETED, 60);
        insertTask(project, "미완료-null", TaskStatus.UNASSIGNED, null);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].warningType").value("TOO_FEW_TASKS"))
                .andExpect(jsonPath("$.data[1].warningType").value("MISSING_ESTIMATES"))
                .andExpect(jsonPath("$.data[1].reason")
                        .value("예상시간이 비어 있는 미완료 태스크가 1건 있습니다."))
                .andExpect(jsonPath("$..reason", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("마감까지")))));
    }

    @Test
    @DisplayName("[G9] CLOSED → 200 빈 배열 (태스크 0건이라 TOO_FEW 조건이 성립하는데도 억제)")
    void closedSuppressedAtApiLevel() throws Exception {
        UUID project = insertProject(MAIN, "CLOSED", null);

        mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ═══════════════ 마감 경계 (고정 시계) ═══════════════

    @Test
    @DisplayName("마감 D+3 → 발생 / D+4 → 미발생 / 당일 → '마감까지 0일'")
    void deadlineBoundaryThroughApi() throws Exception {
        assertDeadlineWarning(TODAY.plusDays(3), true, "마감까지 3일, 미완료 태스크가 3건 남았습니다.");
        assertDeadlineWarning(TODAY.plusDays(4), false, null);
        assertDeadlineWarning(TODAY, true, "마감까지 0일, 미완료 태스크가 3건 남았습니다.");
    }

    // ═══════════════ [G7] 소유 격리 · 결정성 ═══════════════

    @Test
    @DisplayName("[G7] 타인 프로젝트 → 404 · 부재 UUID → 404 (동일 응답 — 구분 불가)")
    void ownershipHidden() throws Exception {
        UUID othersProject = insertProject(OTHER, "IN_PROGRESS", null);

        mockMvc.perform(get(path(othersProject)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
        mockMvc.perform(get(path(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("미시드 사용자 → 401 E-COM-002")
    void unseededUserUnauthorized() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", null);

        mockMvc.perform(get(path(project)).header("X-Dev-User", "99999999-9999-9999-9999-999999999999"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-COM-002"));
    }

    @Test
    @DisplayName("결정성 — 같은 입력 2회 호출이 문자 단위로 동일한 응답")
    void deterministicAcrossCalls() throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", TODAY.plusDays(2));
        insertTask(project, "A", TaskStatus.UNASSIGNED, null);

        String first = body(project);
        String second = body(project);

        assertThat(first).isEqualTo(second);
    }

    // ---------- helpers ----------

    private void assertDeadlineWarning(LocalDate dueDate, boolean expected, String reason) throws Exception {
        UUID project = insertProject(MAIN, "IN_PROGRESS", dueDate);
        insertTask(project, "A", TaskStatus.UNASSIGNED, 60);
        insertTask(project, "B", TaskStatus.UNASSIGNED, 60);
        insertTask(project, "C", TaskStatus.UNASSIGNED, 60); // total=3이라 TOO_FEW 억제

        var actions = mockMvc.perform(get(path(project)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk());
        if (expected) {
            actions.andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].warningType").value("DEADLINE_PRESSURE"))
                    .andExpect(jsonPath("$.data[0].reason").value(reason));
        } else {
            actions.andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    private String body(UUID projectId) throws Exception {
        return mockMvc.perform(get(path(projectId)).header("X-Dev-User", MAIN.toString()))
                .andReturn().getResponse().getContentAsString();
    }

    private String path(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/structure-warnings";
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

    private UUID insertProject(UUID userId, String status, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, '프로젝트', NULL, ?, ?, NULL, ?, 0, ?)
                """, id, userId, dueDate, status,
                "CLOSED".equals(status) ? OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC) : null,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertTask(UUID projectId, String title, TaskStatus status, Integer estimatedMinutes) {
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, NULL, ?, NULL, NULL, ?, 0, ?)
                """, UUID.randomUUID(), projectId, title, estimatedMinutes, status.name(),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
