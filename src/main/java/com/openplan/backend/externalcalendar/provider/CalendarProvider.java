package com.openplan.backend.externalcalendar.provider;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;

import java.time.Instant;
import java.util.List;

/**
 * 제공자별 캘린더 조회 (ST-B1-11).
 *
 * <p><b>인터페이스로 두는 이유가 형식이 아니다.</b> 세 제공자의 사정이 실제로 갈라져 있다 —
 * 구글은 REST 조회가 열려 있고, 카카오는 API 는 있으나 {@code talk_calendar} 사용 권한 승인 전에는
 * 앱 멤버만 호출할 수 있으며, <b>네이버는 오픈 API 에 조회가 없어 프로토콜 자체가 다르다</b>(CalDAV).
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
}
