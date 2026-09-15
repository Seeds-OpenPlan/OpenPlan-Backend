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
     * 읽기만 하던 시절의 형태 — 쓰기 참조도 캘린더 id 도 모르는 호출부(테스트·목)를 그대로 두기 위해 남긴다.
     *
     * <p>이 생성자로 만든 일정은 두 가지가 동시에 성립한다.
     * <ul>
     *   <li>🔴 {@code externalCalendarId} 가 null 이라 <b>삭제 전파의 대상이 되지 않는다</b>
     *       (귀속을 못 하면 지우지 않는다 — #70 리뷰).</li>
     *   <li>같은 이유로 <b>쓰기 대상도 되지 않는다</b> — 쓸 주소를 만들 수 없다(#69).
     *       {@code recurring=false} 이지만 조용히 반복 일정을 쓰게 되는 경로는 없다.</li>
     * </ul>
     */
    public ProviderEvent(String externalEventId, String title, Instant startAt, Instant endAt,
                         String sourceCalendar) {
        this(externalEventId, title, startAt, endAt, sourceCalendar, null, null, null, false);
    }

    /**
     * 캘린더 식별자까지만 알던 시절의 형태 — 삭제 귀속(#70)은 성립하고 <b>쓰기만 되지 않는다</b>.
     *
     * <p>{@code resourceHref}·{@code etag} 가 없어 밖으로 쓸 주소를 만들 수 없고, {@code recurring}
     * 은 false 로 둔다. 쓰기 판정은 {@code ExternalCalendarEvent.isWritable()} 이 식별자와 반복
     * 여부를 함께 보므로, 이 생성자로 만든 일정이 조용히 쓰기 대상이 되지는 않는다.
     */
    public ProviderEvent(String externalEventId, String title, Instant startAt, Instant endAt,
                         String sourceCalendar, String externalCalendarId) {
        this(externalEventId, title, startAt, endAt, sourceCalendar, externalCalendarId, null, null, false);
    }
}
