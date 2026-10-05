package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.provider.ExternalRef;
import com.openplan.backend.externalcalendar.provider.OutboundEvent;

import java.time.Instant;

/**
 * 보낼 때 필요한 값 전부 (#69). 아웃박스 행의 {@code payload} 로 JSONB 에 저장된다.
 *
 * <p>🔴 <b>원본을 다시 읽지 않고 이것만으로 보낼 수 있어야 한다.</b> 삭제는 원본 행이 이미
 * 사라진 뒤에 처리되기 때문이다. 그래서 제목·시각뿐 아니라 <b>대상을 지목하는 참조</b>
 * (event id·href·ETag)까지 여기 담는다.
 *
 * @param writeCalendarId 내보낼 대상 캘린더. 적재 시점의 사용자 설정을 박아 둔다 — 나중에 설정이
 *                        바뀌어도 이미 밖에 있는 일정은 <b>원래 있던 캘린더에서</b> 고쳐야 한다.
 */
public record OutboundPayload(String uid, String title, Instant startAt, Instant endAt,
                              String writeCalendarId,
                              String externalEventId, String resourceHref, String etag) {

    /** 생성·수정용 — 보낼 내용. */
    public OutboundEvent toEvent() {
        return new OutboundEvent(uid, title, startAt, endAt);
    }

    /** 수정·삭제용 — 대상 지목. */
    public ExternalRef toRef() {
        return new ExternalRef(externalEventId, resourceHref, etag);
    }
}
