package com.openplan.backend.schedule.controller;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 일정 편집 API 통합 테스트 (PLAN-17) — {@code PATCH /schedules/{scheduleId}}.
 * 부분 수정·version 낙관락(409)·필드 검증(422)·소유 스코프(404)를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ScheduleApiTest {

    private static final UUID MAIN = UUID.fromString("bbbb2222-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbb2222-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM schedules WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("편집 → 200 · 필드 교체 · version 증가(0→1)")
    void updateOk() throws Exception {
        UUID id = insertSchedule(MAIN, "병원", 60, 2, "메모");

        mockMvc.perform(patch("/api/v1/schedules/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"치과\",\"estimatedMinutes\":30,\"priority\":1,\"memo\":\"변경\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scheduleId").value(id.toString()))
                .andExpect(jsonPath("$.data.title").value("치과"))
                .andExpect(jsonPath("$.data.estimatedMinutes").value(30))
                .andExpect(jsonPath("$.data.priority").value(1))
                .andExpect(jsonPath("$.data.memo").value("변경"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    @DisplayName("부분 수정 — title만 보내면 나머지 유지")
    void updateTitleOnly() throws Exception {
        UUID id = insertSchedule(MAIN, "병원", 60, 2, "메모");

        mockMvc.perform(patch("/api/v1/schedules/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"병원(변경)\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("병원(변경)")) // 바뀜
                .andExpect(jsonPath("$.data.estimatedMinutes").value(60)) // 유지
                .andExpect(jsonPath("$.data.priority").value(2))          // 유지
                .andExpect(jsonPath("$.data.memo").value("메모"));         // 유지
    }

    @Test
    @DisplayName("부분 수정 — estimatedMinutes를 명시적 null로 보내면 해제")
    void updateClearsEstimatedWithNull() throws Exception {
        UUID id = insertSchedule(MAIN, "병원", 60, 2, "메모");

        mockMvc.perform(patch("/api/v1/schedules/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimatedMinutes\":null,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimatedMinutes").doesNotExist());
    }

    @Test
    @DisplayName("version 불일치 → 409 E-COM-006 (latest 동봉)")
    void versionConflict() throws Exception {
        UUID id = insertSchedule(MAIN, "병원", 60, 2, "메모");

        mockMvc.perform(patch("/api/v1/schedules/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"치과\",\"version\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-COM-006"))
                .andExpect(jsonPath("$.error.details.latest.scheduleId").value(id.toString()))
                .andExpect(jsonPath("$.error.details.latest.version").value(0));
    }

    @Test
    @DisplayName("version 누락 → 400")
    void versionRequired() throws Exception {
        UUID id = insertSchedule(MAIN, "병원", 60, 2, "메모");

        mockMvc.perform(patch("/api/v1/schedules/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"치과\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("값 오류 — 예상시간 5분 단위 아님 → 422 (field=estimatedMinutes)")
    void invalidEstimated() throws Exception {
        UUID id = insertSchedule(MAIN, "병원", 60, 2, "메모");

        mockMvc.perform(patch("/api/v1/schedules/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimatedMinutes\":47,\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"))
                .andExpect(jsonPath("$.error.details.fields[0].field").value("estimatedMinutes"));
    }

    @Test
    @DisplayName("없는 일정 → 404")
    void notFound() throws Exception {
        mockMvc.perform(patch("/api/v1/schedules/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"version\":0}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("타인 일정 → 404 (존재 은닉)")
    void otherUserHidden() throws Exception {
        UUID id = insertSchedule(OTHER, "남의일정", 60, 2, "메모");

        mockMvc.perform(patch("/api/v1/schedules/" + id).header("X-Dev-User", MAIN.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"version\":0}"))
                .andExpect(status().isNotFound());
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

    private UUID insertSchedule(UUID userId, String title, Integer estimatedMinutes, Integer priority, String memo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO schedules (schedule_id, user_id, title, estimated_minutes, priority,
                                       start_at, end_at, memo, status, version, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, ?)
                """, id, userId, title, estimatedMinutes, priority,
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(BASE.plusSeconds(3600), ZoneOffset.UTC),
                memo, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        return id;
    }
}
