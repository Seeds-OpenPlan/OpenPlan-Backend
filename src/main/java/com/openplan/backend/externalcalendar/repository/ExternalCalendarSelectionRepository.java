package com.openplan.backend.externalcalendar.repository;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarSelection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * 캘린더 선택 저장소 (ONB-08 · FIX-15). 스코프는 connection_id — 연결 소유자 확인이 선행 조건이다.
 */
public interface ExternalCalendarSelectionRepository extends JpaRepository<ExternalCalendarSelection, UUID> {

    List<ExternalCalendarSelection> findByConnectionIdOrderByCalendarNameAsc(UUID connectionId);

    /** PUT 전체 교체(FIX-15)의 앞 절반 — 빠진 캘린더를 "선택 해제"가 아니라 삭제로 처리한다. */
    void deleteByConnectionId(UUID connectionId);
}
