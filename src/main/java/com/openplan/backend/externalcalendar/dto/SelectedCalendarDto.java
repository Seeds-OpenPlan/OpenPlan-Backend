package com.openplan.backend.externalcalendar.dto;

/** 선택된 캘린더 한 개 — {@code ExternalConnection.selectedCalendars} 항목. */
public record SelectedCalendarDto(String externalCalendarId, String name) {
}
