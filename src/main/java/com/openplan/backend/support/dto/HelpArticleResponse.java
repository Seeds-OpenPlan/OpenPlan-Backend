package com.openplan.backend.support.dto;

import com.openplan.backend.support.domain.HelpArticle;

import java.util.UUID;

/**
 * 도움말 응답 — openapi {@code HelpArticle} 스키마와 1:1(검색용 keywords·sort_order는 미노출).
 */
public record HelpArticleResponse(UUID helpArticleId, String category, String title, String content) {

    public static HelpArticleResponse from(HelpArticle a) {
        return new HelpArticleResponse(a.getHelpArticleId(), a.getCategory(), a.getTitle(), a.getContent());
    }
}
