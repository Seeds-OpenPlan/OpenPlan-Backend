package com.openplan.backend.externalcalendar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 가져올 캘린더 선택 (ONB-08 · FIX-15) — baseline {@code external_calendar_selections} 매핑.
 *
 * <p><b>PUT 은 전체 교체다</b> — 목록에서 빠진 캘린더는 "선택 해제"가 아니라 <b>행 삭제</b>다(FIX-15).
 * UQ(connection_id, external_calendar_id)가 같은 캘린더의 중복 선택을 막는다(B5).
 */
@Getter
@Entity
@Table(name = "external_calendar_selections")
public class ExternalCalendarSelection {

    @Id
    @Column(name = "selection_id")
    private UUID id;

    @Column(name = "connection_id", nullable = false, updatable = false)
    private UUID connectionId;

    /** 제공자 측 캘린더 식별자(구글은 캘린더 주소 형태). */
    @Column(name = "external_calendar_id", nullable = false, length = 255, updatable = false)
    private String externalCalendarId;

    @Column(name = "calendar_name", nullable = false, length = 255)
    private String calendarName;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 전용. */
    protected ExternalCalendarSelection() {
    }

    public static ExternalCalendarSelection select(UUID connectionId, String externalCalendarId,
                                                   String calendarName, Instant now) {
        ExternalCalendarSelection selection = new ExternalCalendarSelection();
        selection.id = UUID.randomUUID();
        selection.connectionId = connectionId;
        selection.externalCalendarId = externalCalendarId;
        selection.calendarName = calendarName;
        selection.active = true;
        selection.createdAt = now;
        return selection;
    }
}
