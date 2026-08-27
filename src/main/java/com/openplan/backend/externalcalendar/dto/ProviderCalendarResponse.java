package com.openplan.backend.externalcalendar.dto;

/**
 * 제공자 캘린더 목록 항목 (ONB-08) — openapi {@code listProviderCalendars}.
 *
 * @param selected 이미 가져오기로 선택한 캘린더인지 — 화면이 체크 상태를 그리는 재료
 */
public record ProviderCalendarResponse(String externalCalendarId, String name, boolean selected) {
}
