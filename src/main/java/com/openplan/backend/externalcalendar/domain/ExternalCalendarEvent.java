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

    /**
     * 이 일정이 온 제공자 캘린더 식별자 — 구글 calendarId · 애플 캘린더 href.
     *
     * <p>🔴 표시 이름({@code sourceCalendar})과 달리 <b>유일하다</b>. 삭제 전파에서 "이번에 조회한
     * 캘린더인가" 를 판정할 때 이름을 쓰면, 같은 이름의 캘린더 둘 중 하나만 선택 해제했을 때
     * 조회하지도 않은 쪽의 일정을 지운다(2026-08-29 리뷰 Blocking).
     */
    @Column(name = "external_calendar_id", length = 512)
    private String externalCalendarId;

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
     * 출처 캘린더 식별자를 최신으로 맞춘다.
     *
     * <p>{@code candidate()}·{@code resync()} 와 나눠 둔 이유: 그 둘은 <b>사용자에게 보이는 값</b>을
     * 다루고 이건 <b>제공자 내부 식별자</b>다. 값이 왔을 때만 갱신한다 — 없다고 지우면 이미 귀속돼
     * 있던 일정이 삭제 대상에서 빠졌다 들어왔다 한다.
     */
    public void locateIn(String externalCalendarId) {
        if (externalCalendarId != null) {
            this.externalCalendarId = externalCalendarId;
        }
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
