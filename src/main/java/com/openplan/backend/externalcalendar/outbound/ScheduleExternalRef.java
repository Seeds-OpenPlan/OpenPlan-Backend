package com.openplan.backend.externalcalendar.outbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 개인 일정 ↔ 외부 일정 매핑 (#69 · {@code schedule_external_refs}).
 *
 * <p>내보낸 시점의 값을 함께 보관한다. 🔴 <b>되받기가 그 차이로만 판정되기 때문이다</b> —
 * 다음 조회에서 제목·시각이 여기 적힌 것과 다르면 그것은 <b>사용자가 외부에서 고친 것</b>이다.
 * 이 스냅샷이 없으면 「우리가 보낸 그대로인가」 를 물을 수 없고, 계획 D4 가 성립하지 않는다.
 */
@Entity
@Table(name = "schedule_external_refs")
public class ScheduleExternalRef {

    @Id
    @Column(name = "schedule_id", nullable = false, updatable = false)
    private UUID scheduleId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "external_uid", nullable = false, length = 255, updatable = false)
    private String externalUid;

    @Column(name = "external_event_id", length = 512)
    private String externalEventId;

    @Column(name = "resource_href", length = 1024)
    private String resourceHref;

    @Column(name = "etag", length = 255)
    private String etag;

    @Column(name = "sent_title", length = 200)
    private String sentTitle;

    @Column(name = "sent_start_at")
    private Instant sentStartAt;

    @Column(name = "sent_end_at")
    private Instant sentEndAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ScheduleExternalRef() {
    }

    /** 아직 내보내기 전 — UID 만 정해 둔다. 참조·스냅샷은 보내고 나서 채운다. */
    public static ScheduleExternalRef reserve(UUID scheduleId, UUID connectionId, String externalUid, Instant now) {
        ScheduleExternalRef ref = new ScheduleExternalRef();
        ref.scheduleId = scheduleId;
        ref.connectionId = connectionId;
        ref.externalUid = externalUid;
        ref.createdAt = now;
        ref.updatedAt = now;
        return ref;
    }

    /** 보내고 나서 — 제공자가 준 참조와 <b>보낸 내용</b>을 함께 적는다. */
    public void recordSent(String externalEventId, String resourceHref, String etag,
                           String title, Instant startAt, Instant endAt, Instant now) {
        if (externalEventId != null) {
            this.externalEventId = externalEventId;
        }
        if (resourceHref != null) {
            this.resourceHref = resourceHref;
        }
        // 🔴 ETag 는 값이 왔을 때만 갱신한다. 없다고 지우면 다음 수정이 If-Match 없이 나가고,
        //    그것은 남의 변경을 말없이 덮는 경로다(제공자 어댑터가 그때 거부한다).
        if (etag != null) {
            this.etag = etag;
        }
        this.sentTitle = title;
        this.sentStartAt = startAt;
        this.sentEndAt = endAt;
        this.updatedAt = now;
    }

    /**
     * 외부에서 고쳐졌는가 — 지금 외부 값이 <b>우리가 보낸 것과 다른가</b>.
     *
     * <p>스냅샷이 없으면(아직 안 보냄) «고쳐졌다» 고 하지 않는다 — 비교할 기준이 없는데 다르다고
     * 답하면 보내지도 않은 일정을 되받게 된다.
     */
    public boolean changedOutside(String title, Instant startAt, Instant endAt) {
        if (sentTitle == null && sentStartAt == null && sentEndAt == null) {
            return false;
        }
        return !java.util.Objects.equals(sentTitle, title)
                || !java.util.Objects.equals(sentStartAt, startAt)
                || !java.util.Objects.equals(sentEndAt, endAt);
    }

    public UUID getScheduleId() {
        return scheduleId;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public String getExternalUid() {
        return externalUid;
    }

    public String getEtag() {
        return etag;
    }

    public String getExternalEventId() {
        return externalEventId;
    }

    public String getResourceHref() {
        return resourceHref;
    }
}
