package com.openplan.backend.externalcalendar.outbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 태스크 블록 ↔ 외부 일정 (#69 · {@code plan_block_external_refs}).
 *
 * <p>🔴 <b>키가 {@code plan_block_id} 가 아니다.</b> 자동 배치는 블록을 지웠다 새 UUID 로 다시
 * 만들고, 주차 이동은 {@code weekly_plan_id} 만 바꾼다. 둘 다 불안정해 (주간계획, 태스크, 순번)을
 * 쓴다. 그리고 <b>UID 는 파생하지 않고 발급받아 이 행이 들고 다닌다</b> — 블록이 다른 주로 가면
 * 이 행의 주간계획만 바뀌고 UID 는 그대로라, 외부에는 <b>수정 한 번</b>이 나간다.
 */
@Entity
@Table(name = "plan_block_external_refs")
public class PlanBlockExternalRef {

    @Id
    @Column(name = "ref_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "weekly_plan_id", nullable = false)
    private UUID weeklyPlanId;

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "external_uid", nullable = false, length = 255, updatable = false)
    private String externalUid;

    @Column(name = "external_event_id", length = 512)
    private String externalEventId;

    @Column(name = "resource_href", length = 1024)
    private String resourceHref;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "sent_title", length = 255)
    private String sentTitle;

    @Column(name = "sent_start_at")
    private Instant sentStartAt;

    @Column(name = "sent_end_at")
    private Instant sentEndAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PlanBlockExternalRef() {
    }

    public static PlanBlockExternalRef reserve(UUID userId, UUID connectionId, UUID weeklyPlanId,
                                               UUID taskId, int sequence, Instant now) {
        PlanBlockExternalRef ref = new PlanBlockExternalRef();
        ref.id = UUID.randomUUID();
        ref.userId = userId;
        ref.connectionId = connectionId;
        ref.weeklyPlanId = weeklyPlanId;
        ref.taskId = taskId;
        ref.sequence = sequence;
        // 🔴 파생하지 않고 발급한다 — 파생할 안정적인 값이 없다(OpenPlanEventUid 클래스 주석).
        ref.externalUid = OpenPlanEventUid.newPlanBlockUid();
        ref.createdAt = now;
        ref.updatedAt = now;
        return ref;
    }

    /** 다른 주로 옮겨 갔다 — UID 는 그대로 두고 소속만 바꾼다. 외부에는 수정 한 번이 나간다. */
    public void movedTo(UUID weeklyPlanId, Instant now) {
        this.weeklyPlanId = weeklyPlanId;
        this.updatedAt = now;
    }

    public void recordSent(String externalEventId, String resourceHref, String etag,
                           String title, Instant startAt, Instant endAt, Instant now) {
        if (externalEventId != null) {
            this.externalEventId = externalEventId;
        }
        if (resourceHref != null) {
            this.resourceHref = resourceHref;
        }
        if (etag != null) {
            this.etag = etag;
        }
        this.sentTitle = title;
        this.sentStartAt = startAt;
        this.sentEndAt = endAt;
        this.updatedAt = now;
    }

    public boolean neverSent() {
        return sentStartAt == null;
    }

    public boolean differsFrom(String title, Instant startAt, Instant endAt) {
        return !Objects.equals(sentTitle, title)
                || !Objects.equals(sentStartAt, startAt)
                || !Objects.equals(sentEndAt, endAt);
    }

    /** 내보낸 시각이 이 창 안이었나 — 외부 삭제 판정의 전제(#69 D4). */
    public boolean wasSentWithin(Instant from, Instant to) {
        return sentStartAt != null && !sentStartAt.isBefore(from) && sentStartAt.isBefore(to);
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

    public UUID getWeeklyPlanId() {
        return weeklyPlanId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public int getSequence() {
        return sequence;
    }

    public String getExternalUid() {
        return externalUid;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public String getResourceHref() {
        return resourceHref;
    }

    public String getEtag() {
        return etag;
    }
}
