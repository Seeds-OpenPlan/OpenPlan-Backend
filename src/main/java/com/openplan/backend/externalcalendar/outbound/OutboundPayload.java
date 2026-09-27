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

    /**
     * 어느 캘린더로 보낼지 (이슈 #69 · #85 리뷰 Blocking).
     *
     * <p><b>이미 내보낸 것은 그것이 실재하는 캘린더로, 아직 안 내보낸 것은 지금 설정된 대상으로.</b>
     * 사용자가 대상을 A→B 로 바꿔도 A 에 있는 이벤트의 수정·삭제는 A 로 나가야 한다 — B 로 보내면
     * 없는 것을 고치려 드는 404 가 영구히 반복된다. 반대로 아직 안 나간 것을 옛 설정으로 보내면
     * 사용자가 이미 버린 캘린더에 새 일정이 생긴다.
     *
     * @param sentCalendarId 매핑이 기억하는 «있는 곳». null 이면 모름(이 컬럼 이전 데이터).
     * @param currentTarget  지금 설정된 대상
     */
    public static String targetCalendar(String sentCalendarId, String currentTarget) {
        return (sentCalendarId != null && !sentCalendarId.isBlank()) ? sentCalendarId : currentTarget;
    }

    /** 생성·수정용 — 보낼 내용. */
    public OutboundEvent toEvent() {
        return new OutboundEvent(uid, title, startAt, endAt);
    }

    /** 수정·삭제용 — 대상 지목. */
    public ExternalRef toRef() {
        return new ExternalRef(externalEventId, resourceHref, etag);
    }
}
