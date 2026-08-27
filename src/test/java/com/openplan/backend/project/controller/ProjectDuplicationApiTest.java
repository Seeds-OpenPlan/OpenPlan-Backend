package com.openplan.backend.project.controller;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 복제 실행 API 통합 테스트 (PROJ-12) — {@code POST /projects/{projectId}/duplications}.
 *
 * <p>고정하는 계약: 새 id로 프로젝트+태스크+WBS 통째 복사 · 복제본 태스크 전량 UNASSIGNED ·
 * WBS는 복제본 태스크로 재연결 · 원본 무변경 · newName 미지정 시 "원본명 (복제)" · 부재·타인 404.
 * 주간 계획 블록·수행이력은 복사 대상이 아니다(프리뷰 note와 동일 규정).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectDuplicationApiTest {

    private static final UUID MAIN = UUID.fromString("a1b1c1d1-0000-0000-0000-000000000011");
    private static final UUID OTHER = UUID.fromString("a1b1c1d1-0000-0000-0000-000000000012");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM wbs_items WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM tasks WHERE project_id IN "
                + "(SELECT project_id FROM projects WHERE user_id IN (?, ?))", MAIN, OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("복제 실행 → 201 · 새 projectId · 이름='원본명 (복제)' · IN_PROGRESS · version=0 · 설명·마감일·우선순위 승계")
    void duplicateOk() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", "3분기 캠페인", "IN_PROGRESS",
                LocalDate.of(2099, 12, 31), 2);

        String body = mockMvc.perform(post(path(source)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.projectId").isNotEmpty())
                .andExpect(jsonPath("$.data.name").value("마케팅 (복제)"))
                .andExpect(jsonPath("$.data.description").value("3분기 캠페인"))
                .andExpect(jsonPath("$.data.dueDate").value("2099-12-31"))
                .andExpect(jsonPath("$.data.priority").value(2))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.closedAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();

        assertThat(newIdOf(body)).isNotEqualTo(source); // 새 id — 덮어쓰기 아님
    }

    @Test
    @DisplayName("newName 지정 → 지정 이름으로 생성")
    void duplicateWithNewName() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", null, "IN_PROGRESS", null, null);

        mockMvc.perform(post(path(source))
                        .header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newName":"  마케팅 2026  "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("마케팅 2026")); // 생성과 동일하게 trim
    }

    @Test
    @DisplayName("newName 공백만 → 미지정 취급('원본명 (복제)') · 빈 본문 {}도 동일")
    void blankNewNameFallsBackToDefault() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", null, "IN_PROGRESS", null, null);

        mockMvc.perform(post(path(source))
                        .header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"newName":"   "}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("마케팅 (복제)"));

        mockMvc.perform(post(path(source))
                        .header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("마케팅 (복제)"));
    }

    @Test
    @DisplayName("자르는 지점이 이모지 한가운데여도 쪼개지 않는다 (짝 잃은 서로게이트 → DB에 '?' 로 박힘)")
    void truncationDoesNotSplitSurrogatePair() throws Exception {
        // 95번째 칸(cut 지점)이 이모지의 상위 서로게이트가 되도록 배치: 94자 + 이모지(2칸) = 96칸
        String longName = "가".repeat(94) + "😀";
        UUID source = insertProject(MAIN, longName, null, "IN_PROGRESS", null, null);

        UUID copy = duplicate(source);

        String copied = jdbc.queryForObject(
                "SELECT name FROM projects WHERE project_id = ?", String.class, copy);
        // 이모지는 통째로 버려진다 — 반쪽만 남기면 '?' 로 치환돼 이름이 조용히 망가진다
        assertThat(copied).isEqualTo("가".repeat(94) + " (복제)");
        assertThat(copied).doesNotContain("?");
        assertThat(copied.chars().noneMatch(c -> Character.isSurrogate((char) c))).isTrue();
    }

    @Test
    @DisplayName("newName 100자 초과 → 422 E-COM-009 (생성과 동일 규칙)")
    void tooLongNewNameRejected() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", null, "IN_PROGRESS", null, null);

        mockMvc.perform(post(path(source))
                        .header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newName\":\"" + "가".repeat(101) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));

        assertThat(projectCountOf(MAIN)).isEqualTo(1); // 검증 실패 → 복제본 미생성(원본만)
    }

    @Test
    @DisplayName("과거 마감일은 승계하지 않는다 → 복제본 dueDate=null (우선순위는 그대로 승계)")
    void pastDueDateNotInherited() throws Exception {
        UUID source = insertProject(MAIN, "지난분기", null, "IN_PROGRESS",
                LocalDate.of(2026, 7, 1), 2); // FIXED_TODAY(2026-07-15) 이전

        mockMvc.perform(post(path(source)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.dueDate").doesNotExist())
                .andExpect(jsonPath("$.data.priority").value(2));
    }

    @Test
    @DisplayName("201의 IN_PROGRESS가 거짓이 아니다 — 자동종료 평가 지점을 지나도 CLOSED로 뒤집히지 않는다")
    void duplicateSurvivesAutoCloseEvaluation() throws Exception {
        UUID source = insertProject(MAIN, "지난분기", null, "IN_PROGRESS",
                LocalDate.of(2026, 7, 1), null);
        UUID copy = duplicate(source);

        // GET /projects = 자동 종료 지연 평가 지점(ADR-0006). 원본은 여기서 CLOSED 되는 게 정상이다.
        mockMvc.perform(get("/api/v1/projects").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk());

        Map<String, Object> copyRow = jdbc.queryForMap(
                "SELECT status, due_date FROM projects WHERE project_id = ?", copy);
        assertThat(copyRow.get("status")).isEqualTo("IN_PROGRESS"); // 승계했으면 CLOSED로 뒤집혔다
        assertThat(copyRow.get("due_date")).isNull();

        assertThat(jdbc.queryForObject("SELECT status FROM projects WHERE project_id = ?",
                String.class, source)).isEqualTo("CLOSED"); // 원본은 원래 규칙대로 종료
    }

    @Test
    @DisplayName("오늘 마감일은 승계 — 경계는 dueDate < today (bulkCloseOverdue·validateDueDate와 동일)")
    void todayDueDateInherited() throws Exception {
        UUID source = insertProject(MAIN, "오늘마감", null, "IN_PROGRESS",
                FixedClockConfig.FIXED_TODAY, null);

        mockMvc.perform(post(path(source)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.dueDate").value("2026-07-15"));
    }

    @Test
    @DisplayName("긴 원본명 — 접미사 붙여도 100자를 넘지 않게 잘라 붙인다(500 방지)")
    void longSourceNameTruncatedToFit() throws Exception {
        String longName = "가".repeat(100); // projects.name VARCHAR(100) 상한
        UUID source = insertProject(MAIN, longName, null, "IN_PROGRESS", null, null);

        UUID copy = duplicate(source);

        String copied = jdbc.queryForObject(
                "SELECT name FROM projects WHERE project_id = ?", String.class, copy);
        assertThat(copied).hasSize(100).endsWith(" (복제)");
    }

    @Test
    @DisplayName("태스크 복제 — 6필드 승계 · 원본 상태와 무관하게 전량 UNASSIGNED · version=0")
    void tasksCopiedAsUnassigned() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", null, "IN_PROGRESS", null, null);
        insertTask(source, "미배치", TaskStatus.UNASSIGNED, "메모A", 60, 1, LocalDate.of(2026, 8, 10));
        insertTask(source, "진행중", TaskStatus.IN_PROGRESS, null, 90, null, null);
        insertTask(source, "완료", TaskStatus.COMPLETED, null, 30, 3, null);

        UUID copy = duplicate(source);

        List<Map<String, Object>> tasks = jdbc.queryForList(
                "SELECT title, status, memo, estimated_minutes, priority, due_date, version "
                        + "FROM tasks WHERE project_id = ? ORDER BY title", copy);
        assertThat(tasks).hasSize(3);
        assertThat(tasks).allSatisfy(t -> {
            assertThat(t.get("status")).isEqualTo(TaskStatus.UNASSIGNED.name()); // 전량 미배치
            assertThat(((Number) t.get("version")).longValue()).isZero();
        });
        Map<String, Object> mibaechi = tasks.stream()
                .filter(t -> "미배치".equals(t.get("title"))).findFirst().orElseThrow();
        assertThat(mibaechi.get("memo")).isEqualTo("메모A");
        assertThat(mibaechi.get("estimated_minutes")).isEqualTo(60);
        assertThat(mibaechi.get("priority")).isEqualTo(1);
        assertThat(mibaechi.get("due_date").toString()).isEqualTo("2026-08-10");
    }

    @Test
    @DisplayName("WBS 복제 — 복제본 태스크로 재연결 · 기간 승계 · 원본 WBS 참조 없음")
    void wbsCopiedAndRelinked() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", null, "IN_PROGRESS", null, null);
        UUID t1 = insertTask(source, "태스크1", TaskStatus.UNASSIGNED, null, 60, null, null);
        UUID t2 = insertTask(source, "태스크2", TaskStatus.UNASSIGNED, null, 60, null, null);
        insertTask(source, "WBS없는태스크", TaskStatus.UNASSIGNED, null, 60, null, null);
        insertWbs(source, t1, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));
        insertWbs(source, t2, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 16));

        UUID copy = duplicate(source);

        // 복제본 프로젝트 안에서 조인이 성립 = task_id가 복제본 태스크를 가리킨다(원본 참조면 0행)
        List<Map<String, Object>> wbs = jdbc.queryForList("""
                SELECT t.title, w.start_date, w.end_date
                FROM wbs_items w JOIN tasks t ON t.task_id = w.task_id AND t.project_id = w.project_id
                WHERE w.project_id = ? ORDER BY t.title
                """, copy);
        assertThat(wbs).hasSize(2); // WBS 없던 태스크는 WBS도 없음
        assertThat(wbs.get(0).get("title")).isEqualTo("태스크1");
        assertThat(wbs.get(0).get("start_date").toString()).isEqualTo("2026-08-03");
        assertThat(wbs.get(0).get("end_date").toString()).isEqualTo("2026-08-09");
        assertThat(wbs.get(1).get("title")).isEqualTo("태스크2");
        assertThat(wbs.get(1).get("start_date").toString()).isEqualTo("2026-08-10");
    }

    @Test
    @DisplayName("원본 무변경 — 이름·상태·version·태스크 상태·WBS 그대로")
    void sourceUntouched() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", "설명", "IN_PROGRESS", null, null);
        UUID t1 = insertTask(source, "완료태스크", TaskStatus.COMPLETED, null, 60, null, null);
        insertWbs(source, t1, LocalDate.of(2026, 8, 3), LocalDate.of(2026, 8, 9));

        duplicate(source);

        Map<String, Object> project = jdbc.queryForMap(
                "SELECT name, status, version FROM projects WHERE project_id = ?", source);
        assertThat(project.get("name")).isEqualTo("마케팅");
        assertThat(project.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(((Number) project.get("version")).longValue()).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT status FROM tasks WHERE task_id = ?", String.class, t1))
                .isEqualTo(TaskStatus.COMPLETED.name());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wbs_items WHERE project_id = ?", Long.class, source))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("CLOSED 원본도 복제 가능 — 복제본은 IN_PROGRESS · closedAt=null")
    void closedSourceDuplicatesAsInProgress() throws Exception {
        UUID source = insertProject(MAIN, "종료프로젝트", null, "CLOSED", null, null);

        mockMvc.perform(post(path(source)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.closedAt").doesNotExist());
    }

    @Test
    @DisplayName("태스크·WBS 없는 프로젝트 → 201 · 복제본도 비어 있음")
    void duplicateEmptyProject() throws Exception {
        UUID source = insertProject(MAIN, "빈프로젝트", null, "IN_PROGRESS", null, null);

        UUID copy = duplicate(source);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE project_id = ?", Long.class, copy)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM wbs_items WHERE project_id = ?", Long.class, copy)).isZero();
    }

    @Test
    @DisplayName("Idempotency-Key 수용 — 헤더가 있어도 서버 dedup은 없다(현 계약: 관측 로그용)")
    void idempotencyKeyAcceptedButNotDeduped() throws Exception {
        UUID source = insertProject(MAIN, "마케팅", null, "IN_PROGRESS", null, null);

        UUID first = duplicateWithKey(source, "key-1");
        UUID second = duplicateWithKey(source, "key-1");

        assertThat(first).isNotEqualTo(second);
        assertThat(projectCountOf(MAIN)).isEqualTo(3); // 원본 + 복제본 2 — dedup은 후속 과제
    }

    @Test
    @DisplayName("없는 프로젝트 → 404 E-COM-004")
    void duplicateNotFound() throws Exception {
        mockMvc.perform(post(path(UUID.randomUUID())).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 프로젝트 → 404 (존재 은닉) · 복제본 미생성")
    void duplicateOtherUserHidden() throws Exception {
        UUID source = insertProject(OTHER, "남의프로젝트", null, "IN_PROGRESS", null, null);
        insertTask(source, "남의태스크", TaskStatus.UNASSIGNED, null, 60, null, null);

        mockMvc.perform(post(path(source)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        assertThat(projectCountOf(MAIN)).isZero();
        assertThat(projectCountOf(OTHER)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private String path(UUID projectId) {
        return "/api/v1/projects/" + projectId + "/duplications";
    }

    /** 본문 없이 복제 실행 후 생성된 projectId 반환. */
    private UUID duplicate(UUID projectId) throws Exception {
        String body = mockMvc.perform(post(path(projectId)).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return newIdOf(body);
    }

    private UUID duplicateWithKey(UUID projectId, String key) throws Exception {
        String body = mockMvc.perform(post(path(projectId))
                        .header("X-Dev-User", MAIN.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return newIdOf(body);
    }

    private UUID newIdOf(String responseBody) {
        return UUID.fromString(JsonPath.read(responseBody, "$.data.projectId"));
    }

    private Long projectCountOf(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM projects WHERE user_id = ?", Long.class, userId);
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

    private UUID insertProject(UUID userId, String name, String description, String status,
                               LocalDate dueDate, Integer priority) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
                """, id, userId, name, description, dueDate, status, priority,
                "CLOSED".equals(status) ? OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC) : null,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private UUID insertTask(UUID projectId, String title, TaskStatus status, String memo,
                            Integer estimatedMinutes, Integer priority, LocalDate dueDate) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tasks (task_id, project_id, category_id, title, memo, estimated_minutes,
                                   priority, due_date, status, version, created_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, ?, ?, 0, ?)
                """, id, projectId, title, memo, estimatedMinutes, priority, dueDate, status.name(),
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }

    private void insertWbs(UUID projectId, UUID taskId, LocalDate startDate, LocalDate endDate) {
        jdbc.update("""
                INSERT INTO wbs_items (wbs_item_id, project_id, task_id, start_date, end_date, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, taskId, startDate, endDate,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
