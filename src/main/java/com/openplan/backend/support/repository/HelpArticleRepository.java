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

    @Query("""
            select h from HelpArticle h
            where h.status = com.openplan.backend.support.domain.HelpArticleStatus.PUBLISHED
              and (:keyword is null
                   or lower(h.title)    like lower(concat('%', :keyword, '%'))
                   or lower(h.keywords) like lower(concat('%', :keyword, '%')))
              and (:category is null or h.category = :category)
            order by h.sortOrder asc
            """)
    List<HelpArticle> search(@Param("keyword") String keyword, @Param("category") String category);
}
