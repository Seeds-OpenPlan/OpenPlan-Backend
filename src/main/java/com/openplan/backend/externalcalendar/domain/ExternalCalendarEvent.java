package com.openplan.backend.externalcalendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 제공자에서 가져온 일정 후보 (ONB-08/09) — baseline {@code external_calendar_events} 매핑.
 *
 * <p><b>동기화는 덮어쓰기가 아니라 갱신이다.</b> UQ(connection_id, external_event_id)로 같은 원본
 * 일정을 한 행에 유지한다 — 매번 지우고 다시 넣으면 사용자가 이미 내린 판단(APPLIED·EXCLUDED)이
 * 사라져 <b>제외한 일정이 되살아난다.</b>
 */
@Getter
@Entity
@Table(name = "external_calendar_events")
public class ExternalCalendarEvent {

    @Id
    @Column(name = "external_calendar_event_id")
    private UUID id;

    /** 제공자 측 원본 이벤트 ID — 재동기화 시 같은 행을 찾는 열쇠. */
    @Column(name = "external_event_id", nullable = false, length = 255, updatable = false)
    private String externalEventId;

    @Column(name = "connection_id", nullable = false, updatable = false)
    private UUID connectionId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    /** 어느 캘린더에서 왔는지(표시용 이름). */
    @Column(name = "source_calendar", length = 255)
    private String sourceCalendar;

    @Enumerated(EnumType.STRING)
    @Column(name = "apply_mode", length = 20)
    private ApplyMode applyMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "apply_status", nullable = false, length = 20)
    private ApplyStatus applyStatus;

    /** 제공자 캘린더 식별자 — 구글 calendarId · 애플 캘린더 href. 쓰기 주소의 앞부분(#69). */
    @Column(name = "external_calendar_id", length = 512)
    private String externalCalendarId;

    /** 애플 CalDAV .ics 리소스 주소(PUT·DELETE 대상). 구글은 null. */
    @Column(name = "resource_href", length = 1024)
    private String resourceHref;

    /** If-Match 용. 🔴 없으면 그 사이 남이 고친 것을 말없이 덮는다. */
    @Column(name = "etag", length = 255)
    private String etag;

    /**
     * 원본이 반복 일정인가.
     *
     * <p>🔴 쓰기를 막는 근거다. 읽기는 회차 단위인데 쓰기는 파일 단위라, 회차 하나를 고치려고
     * PUT 하면 반복 일정 전체가 덮인다(마이그레이션 V202608290200 주석 참조).
     */
    @Column(name = "recurring", nullable = false)
    private boolean recurring;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected ExternalCalendarEvent() {
    }

    public static ExternalCalendarEvent candidate(UUID connectionId, String externalEventId, String title,
                                                  Instant startAt, Instant endAt, String sourceCalendar,
                                                  Instant now) {
        ExternalCalendarEvent event = new ExternalCalendarEvent();
        event.id = UUID.randomUUID();
        event.connectionId = connectionId;
        event.externalEventId = externalEventId;
        event.title = title;
        event.startAt = startAt;
        event.endAt = endAt;
        event.sourceCalendar = sourceCalendar;
        event.applyMode = null;
        event.applyStatus = ApplyStatus.CANDIDATE;
        event.syncedAt = now;
        event.createdAt = now;
        return event;
    }

    /**
     * 재동기화 — 제공자에서 바뀐 내용만 반영한다.
     *
     * <p><b>applyStatus 는 건드리지 않는다.</b> 사용자가 이미 제외했거나 반영한 일정을 후보로 되돌리면
     * 제외가 무의미해진다. 제목·시각이 바뀌었더라도 판단 자체는 사용자의 것이다.
     */
    /**
     * 쓰기 참조를 최신으로 맞춘다 (#69).
     *
     * <p>{@code candidate()}·{@code resync()} 와 나눠 둔 이유: 그 둘은 <b>사용자에게 보이는 값</b>을
     * 다루고 이건 <b>제공자 내부 주소</b>를 다룬다. 시그니처에 섞으면 모든 호출부가 쓰기와 무관한
     * 인자를 들고 다녀야 한다. ETag 는 매 조회마다 바뀔 수 있으므로 값이 왔을 때만 갱신한다 —
     * 없다고 지우면 다음 쓰기가 If-Match 없이 나간다.
     */
    public void updateWriteRefs(String externalCalendarId, String resourceHref, String etag, boolean recurring) {
        if (externalCalendarId != null) {
            this.externalCalendarId = externalCalendarId;
        }
        if (resourceHref != null) {
            this.resourceHref = resourceHref;
        }
        if (etag != null) {
            this.etag = etag;
        }
        this.recurring = recurring;
    }

    /**
     * 밖으로 쓸 수 있는 일정인가 (#69).
     *
     * <p>반복 일정은 제외한다 — 회차 하나를 고치려다 전체를 덮을 수 있다. 캘린더 식별자가 없으면
     * 쓰기 주소를 만들 수 없다. <b>모르면 쓰지 않는다.</b>
     */
    public boolean isWritable() {
        return !recurring && externalCalendarId != null;
    }

    public void resync(String title, Instant startAt, Instant endAt, String sourceCalendar, Instant now) {
        this.title = title;
        this.startAt = startAt;
        this.endAt = endAt;
        this.sourceCalendar = sourceCalendar;
        this.syncedAt = now;
    }

    /** ONB-09 반영 — AS_IS·EDITED 는 APPLIED, EXCLUDE 는 EXCLUDED 로 간다. */
    public void apply(ApplyMode mode) {
        this.applyMode = mode;
        this.applyStatus = (mode == ApplyMode.EXCLUDE) ? ApplyStatus.EXCLUDED : ApplyStatus.APPLIED;
    }

    public boolean isCandidate() {
        return applyStatus == ApplyStatus.CANDIDATE;
    }
}
