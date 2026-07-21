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

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.openplan.backend.support.FixedClockConfig.FIXED_TODAY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 상태 변경 API 통합 테스트 (PROJ-07 / EP-5). 전이표 T1~T6·재개 G-1(Q-C2)·멱등(AC-07-3)·낙관락 검증.
 * 고정 시계로 마감 경과 판정을 결정적으로 고정.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectStatusApiTest {

    private static final String DEV = "X-Dev-User";
    private static final UUID MAIN = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
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

    private String path(UUID id) {
        return "/api/v1/projects/" + id + "/status";
    }

    // ---------- 허용 전이 (AC-07-1) ----------

    @Test
    @DisplayName("T1 IN_PROGRESS→PAUSED · T2 →CLOSED(closedAt set) · version 증가")
    void fromInProgress() throws Exception {
        UUID p1 = seed(ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(patch(path(p1)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAUSED","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.version").value(1));

        UUID p2 = seed(ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(patch(path(p2)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CLOSED","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedAt").isNotEmpty());
    }

    @Test
    @DisplayName("T5 CLOSED→IN_PROGRESS 재개(미래 마감) → closedAt 해제")
    void resumeClosedWithFutureDue() throws Exception {
        UUID p = seed(ProjectStatus.CLOSED, FIXED_TODAY.plusDays(10), 0, BASE);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS","version":0}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.closedAt").doesNotExist());
    }

    // ---------- 불허 전이 (AC-07-2) ----------

    @Test
    @DisplayName("T6 CLOSED→PAUSED → 422 E-PROJ-003")
    void closedToPausedForbidden() throws Exception {
        UUID p = seed(ProjectStatus.CLOSED, null, 0, BASE);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAUSED","version":0}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-003"));
    }

    @Test
    @DisplayName("미정의 status 값 → 422 E-COM-009 (전이 오류와 구분)")
    void invalidStatusValue() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ARCHIVED","version":0}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    // ---------- 멱등 (AC-07-3) ----------

    @Test
    @DisplayName("동일 상태 요청 → 200 no-op, version 미증가. 낡은 version이어도 통과(더블클릭 내성)")
    void idempotentNoop() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, null, 3, null); // 서버 version=3
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS","version":0}""")) // stale version=0이어도
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.version").value(3)); // 미증가
    }

    // ---------- 재개 G-1 가드 (AC-07-4 · Q-C2) ----------

    @Test
    @DisplayName("마감 경과 CLOSED 재개, 새 마감 미동반 → 422 E-PROJ-004")
    void resumeOverdueWithoutNewDueRejected() throws Exception {
        UUID p = seed(ProjectStatus.CLOSED, FIXED_TODAY.minusDays(3), 0, BASE);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS","version":0}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-004"));
    }

    @Test
    @DisplayName("마감 경과 CLOSED 재개 + 새 미래 마감 동반 → 200")
    void resumeOverdueWithNewFutureDue() throws Exception {
        UUID p = seed(ProjectStatus.CLOSED, FIXED_TODAY.minusDays(3), 0, BASE);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS","version":0,"dueDate":"2099-01-01"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.dueDate").value("2099-01-01"));
    }

    @Test
    @DisplayName("마감 경과 CLOSED 재개 + dueDate:null(무기한) → 200")
    void resumeOverdueWithNullDue() throws Exception {
        UUID p = seed(ProjectStatus.CLOSED, FIXED_TODAY.minusDays(3), 0, BASE);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"IN_PROGRESS","version":0,"dueDate":null}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
    }

    @Test
    @DisplayName("비재개 전이에 dueDate 동반 → 422 E-COM-009 (마감일은 재개 전용)")
    void dueDateOnNonResumeRejected() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CLOSED","version":0,"dueDate":"2099-01-01"}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    // ---------- 낙관락 (AC-07-5) ----------

    @Test
    @DisplayName("stale version 상태변경 → 409 E-COM-006")
    void staleVersionConflict() throws Exception {
        UUID p = seed(ProjectStatus.IN_PROGRESS, null, 3, null);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"CLOSED","version":0}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-COM-006"));
    }

    @Test
    @DisplayName("부재 → 404 · version/status 누락 → 400")
    void notFoundAndBadRequest() throws Exception {
        mockMvc.perform(patch(path(UUID.randomUUID())).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAUSED","version":0}"""))
                .andExpect(status().isNotFound());

        UUID p = seed(ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(patch(path(p)).header(DEV, MAIN.toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAUSED"}""")) // version 누락
                .andExpect(status().isBadRequest());
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

    private UUID seed(ProjectStatus status, LocalDate dueDate, long version, Instant closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, due_date, status, closed_at, version, created_at)
                VALUES (?, ?, '상태대상', ?, ?, ?, ?, ?)
                """,
                id, MAIN, dueDate, status.name(),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                version, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
