package com.openplan.backend.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * 도움말/FAQ 엔티티 — {@code help_articles}. 공개 콘텐츠(관리 UI 없음, Flyway 시드 주입 대상)라 읽기 전용.
 * 조회는 PUBLISHED만 노출하며, keywords는 HELP-06 검색용이다(응답 스키마엔 포함하지 않음).
 */
@Entity
@Table(name = "help_articles")
public class HelpArticle {

    @Id
    @Column(name = "help_article_id", nullable = false, updatable = false)
    private UUID helpArticleId;

    /**
     * 주제(FAQ 분류). enum이 아닌 String인 이유 — 코드가 값에 따라 분기하지 않고(검색 필터로 넘기는 게 전부),
     * 콘텐츠는 Flyway 시드로 넣는 데이터라 enum이면 주제 추가에 배포가 필요하다.
     * FAQ 콘텐츠 시드가 확정되면 값 집합을 고정하고 DB CHECK 추가를 검토한다.
     */
    @Column(name = "category", nullable = false, length = 50)
    private String category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "keywords", columnDefinition = "text")
    private String keywords;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private HelpArticleStatus status;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** JPA 전용 기본 생성자. */
    protected HelpArticle() {
    }

    public UUID getHelpArticleId() {
        return helpArticleId;
    }

    public String getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getKeywords() {
        return keywords;
    }

    public HelpArticleStatus getStatus() {
        return status;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
