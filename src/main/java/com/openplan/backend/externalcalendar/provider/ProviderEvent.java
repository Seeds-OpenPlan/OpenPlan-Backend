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
                            String sourceCalendar,
                            String externalCalendarId, String resourceHref, String etag, boolean recurring) {

    /**
     * 읽기만 하던 시절의 형태 — 쓰기 참조를 모르는 호출부(테스트·목)를 그대로 두기 위해 남긴다.
     * 이 생성자로 만든 일정은 {@code recurring=false} 이지만 {@code externalCalendarId} 가 없어
     * 쓰기 대상이 되지 않는다(주소를 만들 수 없다). 조용히 반복 일정을 쓰게 되는 경로는 없다.
     */
    public ProviderEvent(String externalEventId, String title, Instant startAt, Instant endAt,
                         String sourceCalendar) {
        this(externalEventId, title, startAt, endAt, sourceCalendar, null, null, null, false);
    }
}
