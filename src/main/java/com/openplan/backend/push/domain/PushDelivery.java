package com.openplan.backend.push.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 발송 기록 (ADR-0015 결정 ③) — {@code push_deliveries} 매핑. <b>행 자체가 멱등 게이트다</b>: 발송 전에
 * 이 행을 먼저 INSERT하고(UNIQUE 충돌 시 미삽입), 삽입에 성공했을 때만 실제로 보낸다. 그래서 이 엔티티는
 * JPA로 직접 저장하지 않는다 — {@code INSERT ... ON CONFLICT DO NOTHING}이 유일한 쓰기 경로이며
 * {@link com.openplan.backend.push.repository.PushDeliveryRepository#tryMark}가 그 경로다(FixedScheduleWeekExceptionRepository와
 * 같은 패턴 — 유니크 위반을 예외로 받으면 트랜잭션이 rollback-only가 되어 되레 500이 된다).
 */
@Entity
@Table(name = "push_deliveries")
public class PushDelivery {

    @Id
    @Column(name = "push_delivery_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 20)
    private PushDeliveryTargetType targetType;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "start_at", nullable = false, updatable = false)
    private Instant startAt;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    /** JPA 전용(읽기 전용 매핑 — 쓰기는 네이티브 INSERT로만 일어난다). */
    protected PushDelivery() {
    }
}
