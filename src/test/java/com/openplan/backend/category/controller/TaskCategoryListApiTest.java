package com.openplan.backend.category.controller;

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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카테고리 목록 API 통합 테스트 (ST-B2-04 / SC-01 · AC③). 정렬(sort_order ASC, name ASC)·빈 목록·
 * 소유자 스코프를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskCategoryListApiTest {

    private static final String PATH = "/api/v1/task-categories";
    private static final UUID MAIN = UUID.fromString("aaaa2222-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("aaaa2222-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM task_categories WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    @Test
    @DisplayName("AC③ 정렬 — sort_order ASC 우선, 동률이면 name ASC")
    void listSorted() throws Exception {
        insertCategory(MAIN, "가나", 1);   // sort_order 1
        insertCategory(MAIN, "다라", 0);   // sort_order 0
        insertCategory(MAIN, "나다", 0);   // sort_order 0

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                // sort_order 0 그룹(name ASC: 나다 < 다라) 먼저, 그다음 sort_order 1(가나)
                .andExpect(jsonPath("$.data[0].name").value("나다"))
                .andExpect(jsonPath("$.data[1].name").value("다라"))
                .andExpect(jsonPath("$.data[2].name").value("가나"));
    }

    @Test
    @DisplayName("빈 목록 → 200 · data:[]")
    void emptyList() throws Exception {
        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("소유자 스코프 — 타인 카테고리는 제외")
    void userScope() throws Exception {
        insertCategory(MAIN, "내것", 0);
        insertCategory(OTHER, "타인것", 0);

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("내것"));
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

    private void insertCategory(UUID userId, String name, int sortOrder) {
        jdbc.update("""
                INSERT INTO task_categories (task_category_id, user_id, name, sort_order, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), userId, name, sortOrder, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
