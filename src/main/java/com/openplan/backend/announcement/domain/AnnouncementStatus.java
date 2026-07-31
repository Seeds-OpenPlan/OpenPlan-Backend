package com.openplan.backend.announcement.domain;

/**
 * 공지 게시 상태. DB {@code ck_announcement_status}와 1:1. 공개 노출은 {@link #PUBLISHED}만.
 */
public enum AnnouncementStatus {
    /** 게시 중 — 공개 노출 대상 */
    PUBLISHED,
    /** 게시 종료 */
    ENDED,
    /** 숨김 */
    HIDDEN
}
