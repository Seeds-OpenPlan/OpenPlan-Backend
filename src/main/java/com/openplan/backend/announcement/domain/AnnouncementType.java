package com.openplan.backend.announcement.domain;

/**
 * 공지 유형 (OPS-01 "유형" · ST-B1-14 AC③ — Badge 재료 "점검/장애/변경").
 * DB {@code ck_announcement_type} 및 baseline 컬럼과 1:1. enum 고정(문자열 자유입력 금지).
 */
public enum AnnouncementType {
    /** 점검 */
    MAINTENANCE,
    /** 장애 */
    INCIDENT,
    /** 변경 */
    CHANGE
}
