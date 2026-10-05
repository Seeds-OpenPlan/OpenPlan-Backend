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
/*
  🔴 uid 와 externalEventId 는 다르다 — 섞으면 방금 내보낸 일정이 다음 동기화에서 지워진다.

    externalEventId  이 저장소가 쓰는 행 식별자. 구글은 이벤트 id, 애플은 UID#시작시각 합성본이다.
                     반복을 회차로 펼치려면 회차마다 달라야 해서 애플은 접미사를 붙인다.
    uid              iCalendar UID — 제공자가 일정에 붙인 **전역 이름**. 회차가 여럿이어도 하나다.
                     구글은 iCalUID 필드이고 id 가 아니다. 우리가 만든 일정을 알아보는 근거는
                     **이 값**이다(OpenPlanEventUid.isOurs).

  이것을 나누지 않았을 때 무슨 일이 났나(#80 리뷰 Blocking): 구글은 isOurs 가 항상 false 가 돼
  우리 일정이 «외부에서 온 새 후보» 로 다시 들어왔고, 애플은 접미사 때문에 매핑 조회가 어긋나
  «이번에 안 왔다» 로 읽혀 **방금 만든 일정이 삭제 대상이 됐다.**
*/
public record ProviderEvent(String externalEventId, String title, Instant startAt, Instant endAt,
                            String sourceCalendar,
                            String externalCalendarId, String resourceHref, String etag, Boolean recurring,
                            String uid) {

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
        this(externalEventId, title, startAt, endAt, sourceCalendar, null, null, null, null, null);
    }

    /**
     * 캘린더 식별자까지만 알던 시절의 형태 — 삭제 귀속(#70)은 성립하고 <b>쓰기만 되지 않는다</b>.
     *
     * <p>{@code resourceHref}·{@code etag} 가 없어 밖으로 쓸 주소를 만들 수 없고, {@code recurring}
     * 은 <b>null(모름)</b> 이다 — false 로 두면 "반복 아님" 이라는 없는 사실을 주장하게 되고
     * {@code isWritable()} 이 통과시킨다.
     */
    public ProviderEvent(String externalEventId, String title, Instant startAt, Instant endAt,
                         String sourceCalendar, String externalCalendarId) {
        this(externalEventId, title, startAt, endAt, sourceCalendar, externalCalendarId, null, null, null, null);
    }
}
