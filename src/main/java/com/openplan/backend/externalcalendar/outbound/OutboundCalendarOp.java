package com.openplan.backend.externalcalendar.outbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 밖으로 내보낼 변경 한 건 (#69 · {@code outbound_calendar_ops}).
 *
 * <p>🔴 <b>외부 호출을 사용자 요청에 묶지 않기 위한 자리다.</b> 일정 저장이나 계획 확정이 구글
 * 응답을 기다리면, 구글이 느리거나 죽었을 때 OpenPlan 의 수정이 같이 실패한다 — 사용자에게는
 * "내 앱이 고장났다" 로 보인다. 변경은 여기 적고 커밋만 하며, 실제 호출은 다음 동기화가 한다.
 *
 * <p><b>payload 가 왜 필요한가 — 삭제 때문이다.</b> 주간 계획이나 태스크를 지우면
 * {@code ON DELETE CASCADE} 로 원본이 먼저 사라져, 보낼 시점에는 "무엇을 지워야 하는지" 를
 * 알 수 없다. 지우기 전에 담아 둬야 한다.
 */
@Entity
@Table(name = "outbound_calendar_ops")
public class OutboundCalendarOp {

    @Id
    @Column(name = "op_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "connection_id")
    private UUID connectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private OutboundTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation", nullable = false, length = 10)
    private OutboundOperation operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private OutboundPayload payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboundStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboundCalendarOp() {
    }

    public static OutboundCalendarOp queue(UUID userId, UUID connectionId, OutboundTargetType targetType,
                                           UUID targetId, OutboundOperation operation,
                                           OutboundPayload payload, Instant now) {
        OutboundCalendarOp op = new OutboundCalendarOp();
        op.id = UUID.randomUUID();
        op.userId = userId;
        op.connectionId = connectionId;
        op.targetType = targetType;
        op.targetId = targetId;
        op.operation = operation;
        op.payload = payload;
        op.status = OutboundStatus.PENDING;
        op.attempts = 0;
        op.createdAt = now;
        op.updatedAt = now;
        return op;
    }

    /** 나갔다. */
    public void succeed(Instant now) {
        this.status = OutboundStatus.DONE;
        this.attempts++;
        this.lastError = null;
        this.updatedAt = now;
    }

    /**
     * 못 나갔다. <b>사유를 남긴다</b> — 남기지 않으면 "안 나갔다" 만 알고 왜인지 모른다.
     * 상태는 FAILED 지만 다음 동기화가 다시 집는다(영구 포기가 아니다).
     */
    public void fail(String reason, Instant now) {
        this.status = OutboundStatus.FAILED;
        this.attempts++;
        this.lastError = reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000));
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public OutboundTargetType getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public OutboundOperation getOperation() {
        return operation;
    }

    public OutboundPayload getPayload() {
        return payload;
    }

    public OutboundStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
