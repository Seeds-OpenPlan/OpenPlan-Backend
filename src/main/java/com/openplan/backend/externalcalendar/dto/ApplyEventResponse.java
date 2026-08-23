package com.openplan.backend.externalcalendar.dto;

import java.util.UUID;

/**
 * 반영 결과 (ONB-09) — openapi 응답의 {@code applyStatus} + {@code fixedSchedule}.
 * {@code EXCLUDE}면 고정 일정이 생기지 않으므로 {@code fixedScheduleId}가 null 이다.
 */
public record ApplyEventResponse(UUID externalCalendarEventId, String applyStatus, UUID fixedScheduleId) {
}
