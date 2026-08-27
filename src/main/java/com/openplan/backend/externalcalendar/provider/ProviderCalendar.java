package com.openplan.backend.externalcalendar.provider;

/**
 * 제공자 캘린더 한 개 (ONB-08 목록 재료).
 *
 * @param externalCalendarId 제공자 측 식별자 — 선택 저장·일정 조회의 열쇠
 * @param name               표시 이름
 */
public record ProviderCalendar(String externalCalendarId, String name) {
}
