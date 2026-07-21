package com.openplan.backend.project.controller;

import com.openplan.backend.project.entity.ProjectStatus;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.openplan.backend.support.FixedClockConfig.FIXED_TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자동종료 지연평가 경계 매트릭스 (PROJ-08 / AC-08-1~6). evaluator 본체는 R 슬라이스에서 완성·배선됨 —
 * 여기서는 조회(및 쓰기)가 트리거하는 자동종료의 경계·멱등·결정성을 전수 고정한다. 고정 시계로 "오늘" 못 박음.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectAutoCloseApiTest {

    private static final String LIST = "/api/v1/projects";
    private static final String DEV = "X-Dev-User";
    private static final UUID MAIN = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        jdbc.update("DELETE FROM projects WHERE user_id = ?", MAIN);
    }

    @Test
    @DisplayName("AC-08-1 기본 — 어제 마감 IN_PROGRESS는 조회 시 CLOSED로 영속되고 closed_at 기록")
    void basicAutoClose() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, FIXED_TODAY.minusDays(1), 0, null);
        triggerEvaluation();
        assertThat(statusOf(p)).isEqualTo("CLOSED");
        assertThat(closedAtOf(p)).isNotNull();
    }

    @Test
    @DisplayName("AC-08-2 경계 — 어제=종료 / 당일=유지 / 내일=유지 (due_date < today)")
    void dueDateBoundary() throws Exception {
        UUID yesterday = seed(ProjectStatus.IN_PROGRESS, FIXED_TODAY.minusDays(1), 0, null);
        UUID today = seed(ProjectStatus.IN_PROGRESS, FIXED_TODAY, 0, null);
        UUID tomorrow = seed(ProjectStatus.IN_PROGRESS, FIXED_TODAY.plusDays(1), 0, null);

        triggerEvaluation();

        assertThat(statusOf(yesterday)).isEqualTo("CLOSED");
        assertThat(statusOf(today)).isEqualTo("IN_PROGRESS");   // 당일은 살아있음
        assertThat(statusOf(tomorrow)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("AC-08-3 제외 — 무기한(due_date=null)은 종료되지 않는다")
    void nullDueDateExcluded() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, null, 0, null);
        triggerEvaluation();
        assertThat(statusOf(p)).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("AC-08-4 제외 — PAUSED는 마감 지나도 사용자 보류 의사 존중해 유지")
    void pausedExcluded() throws Exception {
        UUID p = seed(ProjectStatus.PAUSED, FIXED_TODAY.minusDays(5), 0, null);
        triggerEvaluation();
        assertThat(statusOf(p)).isEqualTo("PAUSED");
    }

    @Test
    @DisplayName("AC-08-4 멱등 — 이미 CLOSED면 closed_at을 덮어쓰지 않는다")
    void alreadyClosedNotOverwritten() throws Exception {
        Instant originalClosedAt = Instant.parse("2026-06-01T00:00:00Z");
        UUID p = seed(ProjectStatus.CLOSED, FIXED_TODAY.minusDays(1), 0, originalClosedAt);

        triggerEvaluation();

        assertThat(statusOf(p)).isEqualTo("CLOSED");
        assertThat(closedAtOf(p)).isEqualTo(originalClosedAt); // FIXED_NOW로 덮이지 않음
        assertThat(versionOf(p)).isZero();                     // 재갱신 없음
    }

    @Test
    @DisplayName("AC-08-5 · AC-08-6 소생방지·결정성 — 여러 번 평가해도 상태·version 불변(재종료 없음)")
    void deterministicIdempotent() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, FIXED_TODAY.minusDays(1), 5, null);

        triggerEvaluation();               // 1회차: 종료
        assertThat(statusOf(p)).isEqualTo("CLOSED");
        long versionAfterFirst = versionOf(p);
        Instant closedAtAfterFirst = closedAtOf(p);
        assertThat(versionAfterFirst).isEqualTo(6); // 5 → 6 (자동전이도 version+1)

        triggerEvaluation();               // 2회차·3회차: 변화 없어야 함
        triggerEvaluation();
        assertThat(statusOf(p)).isEqualTo("CLOSED");
        assertThat(versionOf(p)).isEqualTo(versionAfterFirst);   // 6 유지 (재종료 없음)
        assertThat(closedAtOf(p)).isEqualTo(closedAtAfterFirst); // 불변
    }

    @Test
    @DisplayName("쓰기 경로도 평가 선행 — 어제 마감 IN_PROGRESS 편집 시도 → 자동종료 후 CLOSED 가드 422")
    void writePathTriggersEvaluation() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, FIXED_TODAY.minusDays(1), 0, null);

        // PUT 편집: 서비스가 평가를 먼저 돌려 CLOSED로 만든 뒤, 종료 가드에 걸린다
        mockMvc.perform(put(LIST + "/" + p).header(DEV, MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"편집 시도","version":0}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-003"));

        assertThat(statusOf(p)).isEqualTo("CLOSED"); // 평가로 실제 종료됨
    }

    // ---------- helpers ----------

    private void triggerEvaluation() throws Exception {
        mockMvc.perform(get(LIST).header(DEV, MAIN.toString())).andExpect(status().isOk());
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM projects WHERE project_id = ?", String.class, id);
    }

    private long versionOf(UUID id) {
        Long v = jdbc.queryForObject("SELECT version FROM projects WHERE project_id = ?", Long.class, id);
        return v == null ? -1 : v;
    }

    private Instant closedAtOf(UUID id) {
        Timestamp t = jdbc.queryForObject("SELECT closed_at FROM projects WHERE project_id = ?", Timestamp.class, id);
        return t == null ? null : t.toInstant();
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

    private UUID seed(ProjectStatus status, LocalDate dueDate, long version, Instant closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, due_date, status, closed_at, version, created_at)
                VALUES (?, ?, '자동종료대상', ?, ?, ?, ?, ?)
                """,
                id, MAIN, dueDate, status.name(),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                version, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
