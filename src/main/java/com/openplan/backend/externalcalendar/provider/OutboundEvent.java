package com.openplan.backend.externalcalendar.provider;

import java.time.Instant;

/**
 * 밖으로 내보낼 일정 한 건 (#69).
 *
 * <p>세 대상(개인 일정·고정 일정 회차·태스크 블록)이 <b>전부 이 한 모양으로</b> 나간다. 고정 일정은
 * 2개월치를 회차로 펼쳐 넣으므로(계획 D1) 반복 규칙이 여기 없고, 그래서 제공자 어댑터가
 * {@code RRULE}·{@code EXDATE} 를 만들 일이 없다 — 애플에서 가장 위험한 연산을 아예 피한다.
 *
 * @param uid    {@code OpenPlanEventUid} 가 만든 값. 🔴 이것이 에코 차단의 근거다 — 다음 동기화가
 *               이 UID 를 보고 «우리 것» 으로 알아보고 새 후보로 만들지 않는다.
 * @param title  사용자가 보는 제목. 가리지 않고 그대로 내보낸다(계획 D6).
 */
public record OutboundEvent(String uid, String title, Instant startAt, Instant endAt) {

    public OutboundEvent {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("uid 없이 내보내면 다음 동기화가 그것을 남의 일정으로 읽는다");
        }
        if (startAt == null || endAt == null || !startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("시작이 끝보다 앞서야 한다: " + startAt + " ~ " + endAt);
        }
    }
}
