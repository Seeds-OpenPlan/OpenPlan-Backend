package com.openplan.backend.support.repository;

import com.openplan.backend.support.domain.HelpArticle;
import com.openplan.backend.support.domain.HelpArticleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 도움말 리포지토리. PUBLISHED만 노출한다. 검색은 keyword(제목·키워드 부분일치)·category(정확일치) 선택 필터.
 */
public interface HelpArticleRepository extends JpaRepository<HelpArticle, UUID> {

    Optional<HelpArticle> findByHelpArticleIdAndStatus(UUID helpArticleId, HelpArticleStatus status);

    /**
     * 도움말 검색 — keyword(제목·키워드 부분일치)·category(정확일치) 둘 다 선택 필터. 미지정은 null로 온다.
     *
     * <p><b>파라미터가 나오는 자리마다 {@code cast(... as string)}을 붙인 이유</b>: null 파라미터를 그대로
     * 두면 JDBC가 타입 미지정(unspecified)으로 보내고, 그러면 PostgreSQL이 {@code '%' || ? || '%'}의
     * {@code ||}를 문자열 결합이 아닌 <b>bytea 결합</b>으로 추론해 {@code lower(bytea)}라는 없는 함수를
     * 찾다가 실패한다 — {@code keyword} 미지정 요청이 전부 500(E-COM-005)으로 샜다.
     *
     * <p>함수 해석은 <b>파싱 단계</b>에서 끝나므로 {@code :keyword is null}로 단락될 것처럼 보여도 소용없다.
     * 그래서 {@code is null} 검사 한 곳이 아니라 <b>모든 출현 위치</b>에 캐스팅이 필요하다(첫 자리만 고치면
     * concat 안쪽에서 같은 오류가 그대로 난다 — 실측).
     */
    @Query("""
            select h from HelpArticle h
            where h.status = com.openplan.backend.support.domain.HelpArticleStatus.PUBLISHED
              and (cast(:keyword as string) is null
                   or lower(h.title)    like lower(concat('%', cast(:keyword as string), '%'))
                   or lower(h.keywords) like lower(concat('%', cast(:keyword as string), '%')))
              and (cast(:category as string) is null or h.category = cast(:category as string))
            order by h.sortOrder asc
            """)
    List<HelpArticle> search(@Param("keyword") String keyword, @Param("category") String category);
}
