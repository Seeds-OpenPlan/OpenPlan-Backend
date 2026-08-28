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
                            String sourceCalendar, String externalCalendarId) {

    /**
     * 표시 이름만 알던 시절의 형태 — 캘린더 id 를 모르는 호출부(테스트·목)를 그대로 두기 위해 남긴다.
     *
     * <p>🔴 이렇게 만든 일정은 {@code externalCalendarId} 가 null 이라 <b>삭제 전파의 대상이 되지
     * 않는다</b>(귀속을 못 하면 지우지 않는다). 조용히 잘못 지우는 경로가 생기지 않는다.
     */
    public ProviderEvent(String externalEventId, String title, Instant startAt, Instant endAt,
                         String sourceCalendar) {
        this(externalEventId, title, startAt, endAt, sourceCalendar, null);
    }
}
