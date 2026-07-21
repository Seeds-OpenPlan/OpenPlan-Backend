package com.openplan.backend.support.dto;

import com.openplan.backend.support.domain.SupportCategory;
import com.openplan.backend.support.domain.SupportTicket;
import com.openplan.backend.support.domain.SupportTicketStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * 문의 응답 — openapi {@code SupportTicket} 스키마와 1:1. {@code hasAnswer}는 파생값(answerContent≠null).
 */
public record SupportTicketResponse(
        UUID supportTicketId,
        SupportCategory category,
        String title,
        String content,
        SupportTicketStatus status,
        boolean hasAnswer,
        String answerContent,
        Instant answeredAt,
        Instant createdAt
) {

    public static SupportTicketResponse from(SupportTicket t) {
        return new SupportTicketResponse(
                t.getSupportTicketId(),
                t.getCategory(),
                t.getTitle(),
                t.getContent(),
                t.getStatus(),
                t.hasAnswer(),
                t.getAnswerContent(),
                t.getAnsweredAt(),
                t.getCreatedAt());
    }
}
