package com.openplan.backend.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 문의 티켓 엔티티 — {@code support_tickets}. 사용자가 등록하고 본인 것만 조회한다(NFR-030 스코핑).
 * 답변은 운영 측이 채우며(관리 UI 없음) 본 스토리는 등록·조회만 담당한다.
 *
 * <p>{@code hasAnswer}는 컬럼이 아니라 {@code answerContent != null} 파생값이다(HELP-02 시각 표시 재료).
 * TEXT 컬럼(content·answer_content)은 {@code columnDefinition="text"}로 매핑한다.
 */
@Entity
@Table(name = "support_tickets")
public class SupportTicket {

    @Id
    @Column(name = "support_ticket_id", nullable = false, updatable = false)
    private UUID supportTicketId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private SupportCategory category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SupportTicketStatus status;

    /** 답변 전 null. non-null이면 hasAnswer=true. */
    @Column(name = "answer_content", columnDefinition = "text")
    private String answerContent;

    @Column(name = "answered_at")
    private Instant answeredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용 기본 생성자. */
    protected SupportTicket() {
    }

    /** 신규 접수 티켓 생성 — 상태 RECEIVED, 답변 없음. id·created_at은 애플리케이션에서 부여. */
    public static SupportTicket create(UUID userId, SupportCategory category, String title, String content) {
        SupportTicket t = new SupportTicket();
        t.supportTicketId = UUID.randomUUID();
        t.userId = userId;
        t.category = category;
        t.title = title;
        t.content = content;
        t.status = SupportTicketStatus.RECEIVED;
        t.createdAt = Instant.now();
        return t;
    }

    public UUID getSupportTicketId() {
        return supportTicketId;
    }

    public UUID getUserId() {
        return userId;
    }

    public SupportCategory getCategory() {
        return category;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public SupportTicketStatus getStatus() {
        return status;
    }

    public String getAnswerContent() {
        return answerContent;
    }

    public Instant getAnsweredAt() {
        return answeredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** HELP-02 답변 유무 파생값. */
    public boolean hasAnswer() {
        return answerContent != null;
    }
}
