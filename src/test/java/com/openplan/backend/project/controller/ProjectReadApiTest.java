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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.openplan.backend.support.FixedClockConfig.FIXED_TODAY;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 프로젝트 조회 API 통합 테스트 (PROJ-01 목록 · PROJ-03 상세 · PROJ-08 자동종료 지연평가 seed).
 *
 * <p>C5 — count/정렬/자동종료 단언은 <b>전용 테스트 사용자</b>(공유 시드 …0001 재사용 금지)로 격리한다.
 * status·due_date·created_at을 JdbcTemplate로 직접 제어해 결정적으로 검증한다(상태변경 슬라이스 이전이라
 * PAUSED/CLOSED는 API로 만들 수 없으므로 직접 시딩).
 *
 * <p><b>C4 — 고정 시계 주입</b>: {@link UserClock}을 고정값 빈으로 덮어써 "오늘"을 못 박는다.
 * 실제 시계/서버 timezone에 의존하면 UTC·Asia/Seoul 날짜가 갈리는 시간대에 자동종료 판정이 flaky해진다
 * (실측: 실제 시계 사용 시 Seoul 자정~오전 9시 구간에서 "당일마감"이 종료돼 버림). 고정 시계로 제거한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class ProjectReadApiTest {

    private static final String PATH = "/api/v1/projects";
    private static final UUID MAIN = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
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

    // ---------- PROJ-01 목록 ----------

    @Test
    @DisplayName("AC-01-1 진행중 탭(IN_PROGRESS,PAUSED) → CLOSED 제외, PAUSED 포함")
    void listProgressGroup() throws Exception {
        insert(MAIN, "진행1", ProjectStatus.IN_PROGRESS, null, BASE, null);
        insert(MAIN, "진행2", ProjectStatus.IN_PROGRESS, null, BASE, null);
        insert(MAIN, "중지1", ProjectStatus.PAUSED, null, BASE, null);
        insert(MAIN, "종료1", ProjectStatus.CLOSED, null, BASE, BASE);

        mockMvc.perform(get(PATH).param("status", "IN_PROGRESS,PAUSED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(3))
                .andExpect(jsonPath("$.data.length()").value(3));

        mockMvc.perform(get(PATH).param("status", "CLOSED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.data[0].closedAt").isNotEmpty());
    }

    @Test
    @DisplayName("AC-01-3 페이지네이션 — page=2&size=20 → 5건 · meta 1-base")
    void pagination() throws Exception {
        for (int i = 0; i < 25; i++) {
            insert(MAIN, "P" + i, ProjectStatus.IN_PROGRESS, null, BASE.plusSeconds(i), null);
        }
        mockMvc.perform(get(PATH).param("page", "2").param("size", "20").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.meta.page.number").value(2))
                .andExpect(jsonPath("$.meta.page.size").value(20))
                .andExpect(jsonPath("$.meta.page.totalElements").value(25))
                .andExpect(jsonPath("$.meta.page.totalPages").value(2));
    }

    @Test
    @DisplayName("AC-01-5 정렬 결정성 — createdAt DESC(최신 먼저), 고정 주입 간격 1초")
    void sortDeterministic() throws Exception {
        UUID oldest = insert(MAIN, "oldest", ProjectStatus.IN_PROGRESS, null, BASE, null);
        UUID mid = insert(MAIN, "mid", ProjectStatus.IN_PROGRESS, null, BASE.plusSeconds(1), null);
        UUID newest = insert(MAIN, "newest", ProjectStatus.IN_PROGRESS, null, BASE.plusSeconds(2), null);

        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].projectId").value(newest.toString()))
                .andExpect(jsonPath("$.data[1].projectId").value(mid.toString()))
                .andExpect(jsonPath("$.data[2].projectId").value(oldest.toString()));
    }

    @Test
    @DisplayName("AC-01-6 · AC-08 목록 조회 시 자동종료 반영 — 어제 마감 IN_PROGRESS → CLOSED로 이동")
    void listTriggersAutoClose() throws Exception {
        insert(MAIN, "기한초과", ProjectStatus.IN_PROGRESS, FIXED_TODAY.minusDays(1), BASE, null);

        // 진행중 탭에는 안 나온다(평가로 CLOSED 전이)
        mockMvc.perform(get(PATH).param("status", "IN_PROGRESS").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(0));
        // 종료 탭에 closedAt과 함께 나온다
        mockMvc.perform(get(PATH).param("status", "CLOSED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].closedAt").isNotEmpty());
    }

    @Test
    @DisplayName("자동종료 제외 sanity — 당일 마감·무기한·PAUSED는 종료되지 않는다(경계 상세는 자동종료 슬라이스)")
    void nonOverdueNotClosed() throws Exception {
        insert(MAIN, "당일마감", ProjectStatus.IN_PROGRESS, FIXED_TODAY, BASE, null);
        insert(MAIN, "무기한", ProjectStatus.IN_PROGRESS, null, BASE, null);
        insert(MAIN, "중지-기한초과", ProjectStatus.PAUSED, FIXED_TODAY.minusDays(5), BASE, null);

        mockMvc.perform(get(PATH).param("status", "IN_PROGRESS,PAUSED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(3)); // 셋 다 그대로
    }

    @Test
    @DisplayName("AC-01-7 빈 목록 → totalElements:0 성공")
    void emptyList() throws Exception {
        mockMvc.perform(get(PATH).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("AC-01-4 페이지 규약 위반 → 400 E-COM-001 (page=0·size=0·size=101)")
    void pagingBounds() throws Exception {
        for (String[] p : new String[][]{{"page", "0"}, {"size", "0"}, {"size", "101"}}) {
            mockMvc.perform(get(PATH).param(p[0], p[1]).header("X-Dev-User", MAIN.toString()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("E-COM-001"));
        }
    }

    @Test
    @DisplayName("M1 — page 비정수(page=abc) → 400 E-COM-001 (ProjectListQuery 바인딩, 500 아님)")
    void nonIntegerPageIsBadRequest() throws Exception {
        mockMvc.perform(get(PATH).param("page", "abc").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("E-COM-001"));
    }

    @Test
    @DisplayName("AC-07-2(목록) 미정의 status 값 → 422 E-COM-009")
    void invalidStatusValue() throws Exception {
        mockMvc.perform(get(PATH).param("status", "ARCHIVED").header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("E-COM-009"));
    }

    // ---------- PROJ-03 상세 ----------

    @Test
    @DisplayName("AC-03-1 상세 → 전 필드 반환")
    void detailAllFields() throws Exception {
        UUID id = insert(MAIN, "상세대상", ProjectStatus.IN_PROGRESS, LocalDate.of(2099, 1, 1), BASE, null);
        mockMvc.perform(get(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectId").value(id.toString()))
                .andExpect(jsonPath("$.data.name").value("상세대상"))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.dueDate").value("2099-01-01"))
                .andExpect(jsonPath("$.data.version").value(0));
    }

    @Test
    @DisplayName("AC-03-2 부재·타인 소유 → 404 E-COM-004 (구분 불가)")
    void detailNotFoundOrOtherOwner() throws Exception {
        mockMvc.perform(get(PATH + "/" + UUID.randomUUID()).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));

        UUID othersProject = insert(OTHER, "타인것", ProjectStatus.IN_PROGRESS, null, BASE, null);
        mockMvc.perform(get(PATH + "/" + othersProject).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("E-COM-004"));
    }

    @Test
    @DisplayName("AC-03-3 상세 조회 시 자동종료 반영 — 어제 마감 IN_PROGRESS 상세 → status=CLOSED")
    void detailTriggersAutoClose() throws Exception {
        UUID id = insert(MAIN, "상세-기한초과", ProjectStatus.IN_PROGRESS, FIXED_TODAY.minusDays(1), BASE, null);
        mockMvc.perform(get(PATH + "/" + id).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedAt").isNotEmpty());
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

    private UUID insert(UUID userId, String name, ProjectStatus status, LocalDate dueDate,
                        Instant createdAt, Instant closedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, ?, ?, NULL, ?, 0, ?)
                """,
                id, userId, name, dueDate, status.name(),
                closedAt == null ? null : OffsetDateTime.ofInstant(closedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        return id;
    }
}
