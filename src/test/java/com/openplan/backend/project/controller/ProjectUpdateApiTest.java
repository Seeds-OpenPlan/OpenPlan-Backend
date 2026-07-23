package com.openplan.backend.project.controller;

import com.openplan.backend.project.domain.ProjectStatus;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 편집 API 통합 테스트 (PROJ-06 / EP-4). 검사 순서 404→409→422·낙관락·CLOSED 가드 검증.
 * 고정 시계({@link FixedClockConfig})로 자동종료 판정을 결정적으로 고정.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectUpdateApiTest {

    private static final String PATH = "/api/v1/projects";
    private static final UUID MAIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("AC-06-1 정상 편집 → 200 · version 증가 · 재조회 반영 · status 불변")
    void updateOk() throws Exception {
        UUID id = insert(MAIN, "원제목", ProjectStatus.IN_PROGRESS, null, 0, null);

        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"수정된 제목","description":"설명","dueDate":"2099-12-31","priority":2,"version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("수정된 제목"))
                .andExpect(jsonPath("$.data.description").value("설명"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));

        // 재조회 반영
        mockMvc.perform(get(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(jsonPath("$.data.name").value("수정된 제목"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("AC-06-1 status 포함 → 400 (편집으로 상태 변경 불가)")
    void statusFieldForbidden() throws Exception {
        UUID id = insert(MAIN, "P", ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","version":0,"status":"CLOSED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("version 누락 → 400")
    void versionRequired() throws Exception {
        UUID id = insert(MAIN, "P", ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("AC-06-2 검증 대칭 — name 공백/키 부재/과거 마감 → 422 (생성과 동일)")
    void validationSymmetric() throws Exception {
        UUID id = insert(MAIN, "P", ProjectStatus.IN_PROGRESS, null, 0, null);
        // 공백
        putExpect422(id, "{\"name\":\"   \",\"version\":0}");
        // name 키 부재 (C2)
        putExpect422(id, "{\"description\":\"no name\",\"version\":0}");
        // 과거 마감일 (Q-J, FIXED_TODAY=2026-07-15)
        putExpect422(id, "{\"name\":\"x\",\"dueDate\":\"2000-01-01\",\"version\":0}");
    }

    @Test
    @DisplayName("AC-06-3 낙관락 충돌 → 409 E-COM-006 + details.latest 동봉")
    void optimisticLockConflict() throws Exception {
        UUID id = insert(MAIN, "P", ProjectStatus.IN_PROGRESS, null, 3, null); // 서버 version=3
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"stale 수정","version":0}
                                """)) // 클라이언트 stale version=0
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-COM-006"))
                .andExpect(jsonPath("$.error.details.latest.version").value(3));
    }

    @Test
    @DisplayName("AC-06-4 PAUSED 편집 → 200 · CLOSED 편집 → 422 E-PROJ-005")
    void editByStatus() throws Exception {
        UUID paused = insert(MAIN, "중지프로젝트", ProjectStatus.PAUSED, null, 0, null);
        mockMvc.perform(put(PATH + "/" + paused).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"중지중 수정","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"));

        UUID closed = insert(MAIN, "종료프로젝트", ProjectStatus.CLOSED, null, 0, BASE);
        mockMvc.perform(put(PATH + "/" + closed).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"종료중 수정","version":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005"));
    }

    @Test
    @DisplayName("마감 경과 PAUSED 편집 — 과거 마감일 유지 → 422 E-PROJ-006 · 미래로 변경/무기한 전환 → 200")
    void editOverduePausedGuardsPastDueDate() throws Exception {
        // FIXED_TODAY=2026-07-15. 마감 경과(2026-07-01) PAUSED 프로젝트 — 자동종료에서 제외되어 편집 대상
        UUID id = insert(MAIN, "경과-중지", ProjectStatus.PAUSED, LocalDate.of(2026, 7, 1), 0, null);

        // 과거 마감일 그대로 편집 → 전용 코드(범용 E-COM-009 아님)
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"제목만 수정","dueDate":"2026-07-01","version":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-006"));

        // 마감일을 오늘 이후로 변경하면 편집 성공 (status는 PAUSED 유지)
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"제목 수정","dueDate":"2099-12-31","version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAUSED"))
                .andExpect(jsonPath("$.data.dueDate").value("2099-12-31"));

        // 무기한(null)으로 비워도 편집 성공 — 앞 편집으로 version=1
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"무기한 전환","dueDate":null,"version":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
    }

    @Test
    @DisplayName("종료 먼저 안내 — 종료된 프로젝트는 낡은 version이어도 409 아닌 422 E-PROJ-005 (User 판정)")
    void closedGuardBeforeVersionCheck() throws Exception {
        // 서버 version=3인 CLOSED 프로젝트에 stale version=0으로 편집 시도
        UUID closed = insert(MAIN, "종료-버전3", ProjectStatus.CLOSED, null, 3, BASE);
        mockMvc.perform(put(PATH + "/" + closed).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"수정 시도","version":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-PROJ-005")); // 충돌(409)이 아니라 "종료라 수정 불가"
    }

    @Test
    @DisplayName("AC-06 부재·타인 소유 → 404")
    void notFoundOrOtherOwner() throws Exception {
        mockMvc.perform(put(PATH + "/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        UUID others = insert(OTHER, "타인것", ProjectStatus.IN_PROGRESS, null, 0, null);
        mockMvc.perform(put(PATH + "/" + others).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"version\":0}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("dueDate null 전송 → 무기한으로 교체 (PUT 전체 교체 의미)")
    void clearDueDate() throws Exception {
        UUID id = insert(MAIN, "P", ProjectStatus.IN_PROGRESS, LocalDate.of(2099, 1, 1), 0, null);
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"무기한 전환","dueDate":null,"version":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dueDate").doesNotExist());
    }

    // ---------- fixtures ----------

    private void putExpect422(UUID id, String body) throws Exception {
        mockMvc.perform(put(PATH + "/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
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

    private UUID insert(UUID userId, String name, ProjectStatus status, LocalDate dueDate,
                        long version, Instant closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, ?, ?, NULL, ?, ?, ?)
                """,
                id, userId, name, dueDate, status.name(),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                version, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
