package com.openplan.backend.externalcalendar.outbound;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * 고정 일정의 한 회차 ↔ 외부 일정 (#69 · {@code fixed_schedule_occurrences}).
 *
 * <p>고정 일정은 주간 반복 패턴인데 밖으로는 <b>회차로 펼쳐</b> 개별 일정으로 나간다(계획 D1).
 * 이 행이 그 회차 하나를 가리킨다. <b>(고정일정, 날짜)가 회차의 이름</b>이라, 시각만 바뀌면
 * 같은 회차를 <b>수정</b>하고 지웠다 다시 만들지 않는다.
 */
@Entity
@Table(name = "fixed_schedule_occurrences")
public class FixedOccurrence {

    @Id
    @Column(name = "occurrence_id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "fixed_schedule_id", nullable = false, updatable = false)
    private UUID fixedScheduleId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "occurrence_date", nullable = false, updatable = false)
    private LocalDate occurrenceDate;

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

    protected FixedOccurrence() {
    }

    public static FixedOccurrence reserve(UUID fixedScheduleId, UUID userId, UUID connectionId,
                                          LocalDate occurrenceDate, Instant now) {
        FixedOccurrence o = new FixedOccurrence();
        o.id = UUID.randomUUID();
        o.fixedScheduleId = fixedScheduleId;
        o.userId = userId;
        o.connectionId = connectionId;
        o.occurrenceDate = occurrenceDate;
        o.externalUid = OpenPlanEventUid.forFixedOccurrence(fixedScheduleId, occurrenceDate);
        o.createdAt = now;
        o.updatedAt = now;
        return o;
    }

    /** 보내고 나서 — 제공자가 준 참조와 보낸 내용을 함께 적는다. */
    public void recordSent(String externalEventId, String resourceHref, String etag,
                           String title, Instant startAt, Instant endAt, Instant now) {
        if (externalEventId != null) {
            this.externalEventId = externalEventId;
        }
        if (resourceHref != null) {
            this.resourceHref = resourceHref;
        }
        if (etag != null) {
            this.etag = etag;   // 없다고 지우면 다음 수정이 If-Match 없이 나간다
        }
        this.sentTitle = title;
        this.sentStartAt = startAt;
        this.sentEndAt = endAt;
        this.updatedAt = now;
    }

    /** 아직 한 번도 안 보냈는가 — 보냈으면 UPDATE, 아니면 CREATE 다. */
    public boolean neverSent() {
        return sentStartAt == null;
    }

    /** 보낸 내용과 지금 계산된 값이 다른가 — 다르면 밖에 있는 것을 고쳐야 한다. */
    public boolean differsFrom(String title, Instant startAt, Instant endAt) {
        return !Objects.equals(sentTitle, title)
                || !Objects.equals(sentStartAt, startAt)
                || !Objects.equals(sentEndAt, endAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getFixedScheduleId() {
        return fixedScheduleId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public LocalDate getOccurrenceDate() {
        return occurrenceDate;
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
