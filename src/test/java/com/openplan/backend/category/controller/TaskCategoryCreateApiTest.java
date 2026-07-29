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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.UUID;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 카테고리 생성 API 통합 테스트 (ST-B2-04 / SC-01 · AC①). 정상 생성·이름 중복 409·이름 규칙 422·
 * 사용자 격리(사용자별 UNIQUE)를 고정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class TaskCategoryCreateApiTest {

    private static final String PATH = "/api/v1/task-categories";
    private static final UUID MAIN = UUID.fromString("aaaa1111-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("aaaa1111-0000-0000-0000-000000000002");

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
    @DisplayName("정상 생성 → 201 · name·sortOrder=0·taskCategoryId·createdAt")
    void createOk() throws Exception {
        post(MAIN, "{\"name\":\"업무\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.taskCategoryId").exists())
                .andExpect(jsonPath("$.data.name").value("업무"))
                .andExpect(jsonPath("$.data.sortOrder").value(0))
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    @Test
    @DisplayName("AC① 사용자 내 이름 중복 → 409 E-CAT-001")
    void duplicateNameConflict() throws Exception {
        post(MAIN, "{\"name\":\"업무\"}").andExpect(status().isCreated());

        post(MAIN, "{\"name\":\"업무\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-CAT-001"));

        // trim 후 동일 이름도 중복
        post(MAIN, "{\"name\":\"  업무  \"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("E-CAT-001"));
    }

    @Test
    @DisplayName("사용자 격리 — 다른 사용자는 같은 이름 생성 가능(UNIQUE는 사용자별)")
    void sameNameDifferentUserOk() throws Exception {
        post(MAIN, "{\"name\":\"개인\"}").andExpect(status().isCreated());
        post(OTHER, "{\"name\":\"개인\"}").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("name 규칙 — 공백만/키 부재/50자 초과 → 422 E-COM-009 · 정확히 50자 성공 · trim 영속")
    void nameRules() throws Exception {
        post(MAIN, "{\"name\":\"   \"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));

        post(MAIN, "{\"memo\":\"이름 키 없음\"}")   // name 키 부재(null) → 500 아니라 422
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));

        String over = "가".repeat(51);
        post(MAIN, "{\"name\":\"" + over + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.details.fields[0].message").value("이름은 50자 이하여야 합니다."));

        String exact = "가".repeat(50);
        post(MAIN, "{\"name\":\"  " + exact + "  \"}")   // trim 후 정확히 50자
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(exact));
    }

    // ---------- fixtures ----------

    private ResultActions post(UUID userId, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(PATH).header("X-Dev-User", userId.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
}
