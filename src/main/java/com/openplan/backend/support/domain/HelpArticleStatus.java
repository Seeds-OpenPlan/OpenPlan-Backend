package com.openplan.backend.support.domain;

/**
 * 도움말 공개 상태 — help_articles.status(CHECK ck_help_status). 조회는 PUBLISHED만 노출.
 */
public enum HelpArticleStatus {
    PUBLISHED, HIDDEN
}
