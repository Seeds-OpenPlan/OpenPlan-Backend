package com.openplan.backend.externalcalendar.dto;

/**
 * 내보낼 대상 캘린더 지정 (이슈 #69) — openapi {@code setWriteCalendar}.
 *
 * <p><b>검증 애너테이션을 일부러 붙이지 않았다.</b> {@code null}·생략·빈 문자열이 모두
 * «내보내지 않음» 으로의 해제이기 때문이다 — {@code @NotBlank} 를 붙이면 해제할 경로가 사라진다.
 * 값이 왔을 때 그것이 <b>실재하는 캘린더인지</b>는 서비스가 제공자 목록과 대조해 판정한다.
 */
public record WriteCalendarRequest(String externalCalendarId) {
}
