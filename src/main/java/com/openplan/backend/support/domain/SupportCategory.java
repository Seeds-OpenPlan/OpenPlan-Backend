package com.openplan.backend.support.domain;

/**
 * 문의 분류 — support_tickets.category(VARCHAR(30)). openapi enum과 1:1(BUG/ACCOUNT/PLAN/ETC).
 * (DB엔 category CHECK가 없어 방어는 이 enum 검증에 의존.)
 */
public enum SupportCategory {
    BUG, ACCOUNT, PLAN, ETC
}
