package com.openplan.backend.externalcalendar.provider;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;

import java.time.Instant;
import java.util.List;

/**
 * 제공자별 캘린더 조회 (ST-B1-11).
 *
 * <p><b>인터페이스로 두는 이유가 형식이 아니다.</b> 세 제공자의 사정이 실제로 갈라져 있다 —
 * 구글은 REST 조회가 열려 있고, 카카오는 API 는 있으나 {@code talk_calendar} 사용 권한 승인 전에는
 * 앱 멤버만 호출할 수 있으며, <b>애플은 오픈 API 에 조회가 없어 프로토콜 자체가 다르다</b>(CalDAV).
 * 어떤 제공자가 어떤 방식으로 붙든 위 계층이 바뀌지 않도록 여기서 끊는다.
 *
 * <p>구현체는 실패를 {@link com.openplan.backend.global.error.OpenPlanException}
 * {@code E-EXT-001}(502)로 올린다 — 타임아웃 연결 3초·응답 10초, <b>서버 자동 재시도 없음</b>(AC1).
 */
public interface CalendarProvider {

    /** 이 구현이 담당하는 제공자. */
    ExternalCalendarProvider provider();

    /** 사용자의 캘린더 목록 (ONB-08). */
    List<ProviderCalendar> listCalendars(ProviderCredential credential);

    /**
     * 선택한 캘린더의 기간 내 일정 (ONB-08/09).
     *
     * @param from 조회 시작(포함)
     * @param to   조회 종료(제외)
     */
    List<ProviderEvent> listEvents(ProviderCredential credential, String externalCalendarId, String calendarName,
                                   Instant from, Instant to);

    // ─────────────────────────────────────────── 쓰기 (#69 양방향)
    //
    // 🔴 읽기와 달리 **되돌릴 수 없다.** 잘못 쓰면 사용자의 실제 캘린더가 바뀌고, 우리에게는
    //    되돌릴 재료가 없다. 그래서 세 메서드 모두 «모르면 쓰지 않는다» 를 계약으로 갖는다 —
    //    ExternalRef 에 ETag 가 없으면 수정·삭제를 거부한다(남의 변경을 말없이 덮지 않기 위해).

    /**
     * 새 일정을 만든다.
     *
     * <p>같은 UID 가 이미 있으면 <b>만들지 않고</b> {@link ProviderWriteConflictException} 을 던진다 —
     * 응답이 끊겨 재시도할 때 같은 일정이 둘 생기는 것을 막는다.
     *
     * @param externalCalendarId 대상 캘린더(구글 calendarId · 애플 캘린더 href). 호출부가 사용자 설정
     *                           ({@code write_calendar_id})에서 가져오며, 없으면 부르지 않는다.
     */
    ProviderWriteResult createEvent(ProviderCredential credential, String externalCalendarId, OutboundEvent event);

    /**
     * 이미 있는 일정을 고친다. {@code ref.etag()} 가 없으면 <b>거부한다.</b>
     *
     * <p>그 사이 남이 고쳤으면 {@link ProviderWriteConflictException} — 덮지 않는다.
     * 대상이 이미 없으면 {@link ProviderWriteResult} 대신 예외 없이 <b>다시 만들지 않는다</b>:
     * 구현은 404 를 «사용자가 지웠다» 로 읽고 호출부가 매핑을 정리하도록 예외를 올린다.
     */
    ProviderWriteResult updateEvent(ProviderCredential credential, String externalCalendarId,
                                    ExternalRef ref, OutboundEvent event);

    /**
     * 일정을 지운다. {@code ref.etag()} 가 없으면 <b>거부한다.</b>
     *
     * <p>이미 없으면(404) 성공으로 친다 — 지우려던 결과가 이미 이루어져 있다. 그것을 실패로 올리면
     * 아웃박스가 영원히 재시도한다.
     */
    void deleteEvent(ProviderCredential credential, String externalCalendarId, ExternalRef ref);
}
