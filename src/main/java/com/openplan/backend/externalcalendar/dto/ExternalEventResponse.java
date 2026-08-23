package com.openplan.backend.externalcalendar.dto;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;

import java.time.Instant;
import java.util.UUID;

/** 후보 일정 응답 (ONB-08/09) — openapi {@code ExternalEvent}. */
public record ExternalEventResponse(
        UUID externalCalendarEventId,
        String title,
        Instant startAt,
        Instant endAt,
        String sourceCalendar,
        String applyStatus) {

    public static ExternalEventResponse of(ExternalCalendarEvent event) {
        return new ExternalEventResponse(
                event.getId(),
                event.getTitle(),
                event.getStartAt(),
                event.getEndAt(),
                event.getSourceCalendar(),
                event.getApplyStatus().name());
    }
}
