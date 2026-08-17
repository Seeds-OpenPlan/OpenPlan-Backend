package com.openplan.backend.global.security;

import com.openplan.backend.support.TestcontainersConfig;
import jakarta.servlet.http.Cookie;
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
 * <b>D-16 무손실 교체 증명</b> — dev 스텁을 내리고 {@link JwtCookieAuthFilter}로 갈아끼웠을 때
 * {@code @CurrentUser} 뒤 도메인 코드가 그대로 도는지 검증한다 (ADR-0010 · 4주차 ⓪).
 *
 * <p>다른 통합 테스트와 달리 <b>{@code op.auth.dev-stub=false}</b>로 띄운다 — 즉 이 클래스만
 * 운영과 같은 필터 구성으로 돈다. {@code test} 프로파일은 유지해 Flyway {@code migration-dev}(시드)와
 * Testcontainers는 그대로 쓴다.
 *
 * <p><b>왜 이 테스트가 필요한가.</b> "인증을 붙였다"는 것과 "붙여도 기존 소유자 격리가 그대로 산다"는
 * 다른 명제다. 후자가 D-16이 인증을 4주차로 미루면서 건 전제이며, 깨지면 그 시점의 판단이 무효가 된다.
 * 주체 주입원이 바뀌었는데 격리가 유지되는지는 <b>도메인 API를 실제로 호출해야</b> 알 수 있다.
 */
@SpringBootTest(properties = {
        "op.auth.dev-stub=false",
        // 테스트 전용 키. 32바이트 이상이어야 JwtProperties 가 기동을 허용한다(HS256).
        "op.auth.jwt.secret=test-only-secret-value-that-is-long-enough-32"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class JwtCookieAuthFilterTest {

    private static final String PROJECTS = "/api/v1/projects";
    private static final UUID MAIN = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final Instant BASE = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        seedUser(MAIN);
        seedUser(OTHER);
        jdbc.update("DELETE FROM projects WHERE user_id IN (?, ?)", MAIN, OTHER);
    }

    // ---------- 무손실 교체 본증명 ----------

    @Test
    @DisplayName("유효 op_at 쿠키 → 도메인 API가 그대로 돌고 소유자 격리가 유지된다 (D-16 핵심)")
    void validCookieKeepsOwnerScoping() throws Exception {
        insert(MAIN, "내 프로젝트");
        insert(OTHER, "남의 프로젝트");

        mockMvc.perform(get(PROJECTS).cookie(accessCookie(MAIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].name").value("내 프로젝트"));
    }

    @Test
    @DisplayName("주체가 쿠키에 따라 바뀐다 — 같은 요청도 다른 사용자 것만 보인다")
    void principalFollowsCookie() throws Exception {
        insert(MAIN, "내 프로젝트");
        insert(OTHER, "남의 프로젝트");

        mockMvc.perform(get(PROJECTS).cookie(accessCookie(OTHER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page.totalElements").value(1))
                .andExpect(jsonPath("$.data[0].name").value("남의 프로젝트"));
    }

    // ---------- 미인증 표면 ----------

    @Test
    @DisplayName("쿠키 없음 → 401 E-COM-002 (기존 entryPoint 봉투 그대로)")
    void noCookieIsUnauthorized() throws Exception {
        mockMvc.perform(get(PROJECTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-COM-002"));
    }

    @Test
    @DisplayName("위조 서명 토큰 → 401 (검증 실패는 예외가 아니라 미인증으로 흡수된다)")
    void tamperedTokenIsUnauthorized() throws Exception {
        String forged = jwtService.issueAccessToken(MAIN) + "x";
        mockMvc.perform(get(PROJECTS).cookie(new Cookie(AuthCookies.ACCESS, forged)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-COM-002"));
    }

    @Test
    @DisplayName("🔴 dev-stub=false 에서 X-Dev-User 헤더는 무시된다 — 헤더만으로 남이 될 수 없다")
    void devHeaderIgnoredWhenStubOff() throws Exception {
        insert(MAIN, "내 프로젝트");

        mockMvc.perform(get(PROJECTS).header("X-Dev-User", MAIN.toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("E-COM-002"));
    }

    // ---------- 설계 판단 ③ 검증 ----------

    @Test
    @DisplayName("필터가 401을 직접 쓰지 않는다 — 공개 경로는 쿠키 없이도 열려 있다")
    void publicPathStaysOpenWithoutCookie() throws Exception {
        mockMvc.perform(get("/api/v1/landing"))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private Cookie accessCookie(UUID userId) {
        return new Cookie(AuthCookies.ACCESS, jwtService.issueAccessToken(userId));
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

    private void insert(UUID userId, String name) {
        jdbc.update("""
                INSERT INTO projects (project_id, user_id, name, description, due_date, status,
                                      priority, closed_at, version, created_at)
                VALUES (?, ?, ?, NULL, NULL, 'IN_PROGRESS', NULL, NULL, 0, ?)
                """,
                UUID.randomUUID(), userId, name, OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
    }
}
