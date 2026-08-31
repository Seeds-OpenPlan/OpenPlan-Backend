package com.openplan.backend.support.repository;

import com.openplan.backend.support.FixedClockConfig;
import com.openplan.backend.support.TestcontainersConfig;
import com.openplan.backend.support.dto.HelpArticleResponse;
import com.openplan.backend.support.service.HelpArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 도움말 검색 통합 테스트 (HELP-06) — <b>실제 PostgreSQL에 쿼리를 실행한다</b>.
 *
 * <p>기존 {@code HelpArticleServiceTest}·{@code SupportControllerTest}는 저장소를 목으로 두어
 * "빈 문자열 → null 정규화"까지만 검증했고, 그 null이 DB에서 어떻게 되는지는 한 번도 실행하지 않았다.
 * 그래서 keyword 미지정 요청이 전부 500으로 나가는 것을 배포 전에 잡지 못했다
 * ({@code lower(bytea) does not exist} — {@link HelpArticleRepository#search} javadoc 참고).
 * 이 테스트의 존재 이유가 그것이라, 목을 쓰지 않는 것이 요건이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfig.class, FixedClockConfig.class})
class HelpArticleSearchIntegrationTest {

    private static final String PLAN = "PLAN";
    private static final String ACCOUNT = "ACCOUNT";

    @Autowired
    private HelpArticleService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // 이 테스트가 넣는 카테고리만 정리한다(공유 컨테이너라 무조건 비우지 않는다).
        jdbc.update("DELETE FROM help_articles WHERE category IN (?, ?)", PLAN, ACCOUNT);
        insert(PLAN, "주간 계획 저장 방법", "계획 저장", 1, "PUBLISHED");
        insert(ACCOUNT, "비밀번호 변경 방법", "계정 비밀번호", 2, "PUBLISHED");
        insert(PLAN, "아직 공개 전 문서", "계획 초안", 3, "HIDDEN");
    }

    @Test
    @DisplayName("필터 없음(keyword·category 모두 null) — PUBLISHED 전량, sortOrder 순")
    void searchWithoutAnyFilter() {
        List<HelpArticleResponse> result = service.search(null, null);

        assertEquals(2, result.size()); // HIDDEN 제외
        assertEquals("주간 계획 저장 방법", result.get(0).title()); // sortOrder asc
        assertEquals("비밀번호 변경 방법", result.get(1).title());
    }

    @Test
    @DisplayName("category만 지정(keyword null) — 해당 카테고리만")
    void searchByCategoryOnly() {
        List<HelpArticleResponse> result = service.search(null, PLAN);

        assertEquals(1, result.size()); // HIDDEN 은 카테고리가 같아도 제외
        assertEquals("주간 계획 저장 방법", result.get(0).title());
    }

    @Test
    @DisplayName("빈 문자열 파라미터 — 필터 없음과 같게 취급(blank→null 정규화)")
    void blankParamsBehaveAsNoFilter() {
        assertEquals(2, service.search("", "   ").size());
    }

    @Test
    @DisplayName("keyword — 제목 부분일치")
    void searchByKeywordInTitle() {
        List<HelpArticleResponse> result = service.search("비밀번호", null);

        assertEquals(1, result.size());
        assertEquals("비밀번호 변경 방법", result.get(0).title());
    }

    @Test
    @DisplayName("keyword — keywords 컬럼도 부분일치 대상")
    void searchByKeywordInKeywordsColumn() {
        List<HelpArticleResponse> result = service.search("계정", null);

        assertEquals(1, result.size()); // 제목에는 없고 keywords 에만 있는 말
        assertEquals("비밀번호 변경 방법", result.get(0).title());
    }

    @Test
    @DisplayName("keyword + category 동시 지정 — 둘 다 만족하는 것만")
    void searchByKeywordAndCategory() {
        assertEquals(1, service.search("계획", PLAN).size());
        assertTrue(service.search("계획", ACCOUNT).isEmpty()); // 카테고리 불일치
    }

    @Test
    @DisplayName("0건은 오류가 아니다 — 빈 목록(AC4)")
    void noMatchReturnsEmptyList() {
        assertTrue(service.search("존재하지않는말", null).isEmpty());
        assertTrue(service.search(null, "없는카테고리").isEmpty());
    }

    private void insert(String category, String title, String keywords, int sortOrder, String status) {
        jdbc.update("""
                INSERT INTO help_articles (help_article_id, category, title, content, keywords,
                                           target_audience, sort_order, status, created_at)
                VALUES (?, ?, ?, '본문', ?, NULL, ?, ?, now())
                """, UUID.randomUUID(), category, title, keywords, sortOrder, status);
    }
}
