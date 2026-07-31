package com.openplan.backend.support.domain;

/**
 * 문의 처리 상태 — support_tickets.status(CHECK ck_ticket_status). 접수 시 RECEIVED.
 * 답변/종료는 운영 측 처리(관리 UI 없음 — MVP 범위 밖).
 */
public enum SupportTicketStatus {
    RECEIVED, IN_PROGRESS, ANSWERED, CLOSED
}
