package com.openplan.backend.externalcalendar.outbound;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface ScheduleExternalRefRepository extends JpaRepository<ScheduleExternalRef, UUID> {

    Optional<ScheduleExternalRef> findByExternalUid(String externalUid);

    /**
     * 매핑을 <b>잠그며</b> 읽는다 — 일정 삭제와 실행기가 같은 행을 두고 차례를 지킨다.
     * 보내는 중에 들어온 삭제는 그 결과가 커밋될 때까지 기다린다({@link OutboundOpExecutor}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ScheduleExternalRef> findLockedByScheduleId(UUID scheduleId);

    java.util.List<ScheduleExternalRef> findByConnectionId(java.util.UUID connectionId);
}
