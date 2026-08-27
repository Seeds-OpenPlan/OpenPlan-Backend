package com.openplan.backend.externalcalendar.provider;

import java.time.Instant;

/**
 * 제공자에서 가져온 일정 한 건 (ONB-08/09 후보 재료).
 *
 * <p><b>종일 일정은 여기까지 오지 않는다</b> — 시각이 없어 고정 일정(요일+시분)으로 옮길 수 없다.
 * 거르는 지점은 각 제공자 어댑터다(사유는 {@code GoogleCalendarProvider} 참조).
 *
 * @param externalEventId 제공자 측 원본 이벤트 ID — 재동기화에서 같은 행을 찾는 열쇠
 * @param sourceCalendar  어느 캘린더에서 왔는지(표시용)
 */
public record ProviderEvent(String externalEventId, String title, Instant startAt, Instant endAt,
                            String sourceCalendar) {
}
