package com.openplan.backend.externalcalendar.repository;

import com.openplan.backend.externalcalendar.domain.ApplyStatus;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 후보 일정 저장소 (ONB-08/09). 스코프는 connection_id — 연결 소유자 확인이 선행 조건이다.
 */
public interface ExternalCalendarEventRepository extends JpaRepository<ExternalCalendarEvent, UUID> {

    List<ExternalCalendarEvent> findByConnectionIdOrderByStartAtAsc(UUID connectionId);

    List<ExternalCalendarEvent> findByConnectionIdAndApplyStatusOrderByStartAtAsc(UUID connectionId,
                                                                                  ApplyStatus applyStatus);

    /** 재동기화에서 같은 원본 일정을 찾는 경로 — UQ(connection_id, external_event_id)와 짝이다. */
    Optional<ExternalCalendarEvent> findByConnectionIdAndExternalEventId(UUID connectionId, String externalEventId);

    /** 동기화 1회에 필요한 기존 행 전량 — 건별 조회를 N번 하지 않기 위해 한 번에 읽는다. */
    List<ExternalCalendarEvent> findByConnectionId(UUID connectionId);
}
