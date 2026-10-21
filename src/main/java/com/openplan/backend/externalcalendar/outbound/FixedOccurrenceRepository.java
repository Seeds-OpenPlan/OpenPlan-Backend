package com.openplan.backend.externalcalendar.outbound;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FixedOccurrenceRepository extends JpaRepository<FixedOccurrence, UUID> {

    List<FixedOccurrence> findByUserId(UUID userId);

    /**
     * 고정 일정을 지울 때 회차를 <b>잠그며</b> 읽는다. 그 회차를 지금 내보내는 실행기가 있으면 그 커밋을
     * 기다렸다가 결과(나갔는지·이벤트 ID·ETag)를 보고 삭제를 적는다 — {@link OutboundOpExecutor} 가
     * 보내는 동안 같은 행을 쥐고 있다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<FixedOccurrence> findLockedByFixedScheduleId(UUID fixedScheduleId);

    /** 실행기가 보내는 동안 쥐는 잠금 — {@link #findLockedByFixedScheduleId} 와 짝이다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<FixedOccurrence> findLockedById(UUID id);

    Optional<FixedOccurrence> findByExternalUid(String externalUid);
}
