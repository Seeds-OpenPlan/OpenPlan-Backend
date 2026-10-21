package com.openplan.backend.externalcalendar.outbound;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanBlockExternalRefRepository extends JpaRepository<PlanBlockExternalRef, UUID> {

    List<PlanBlockExternalRef> findByWeeklyPlanId(UUID weeklyPlanId);

    List<PlanBlockExternalRef> findByUserId(UUID userId);

    List<PlanBlockExternalRef> findByConnectionId(UUID connectionId);

    List<PlanBlockExternalRef> findByUserIdAndTaskId(UUID userId, UUID taskId);

    Optional<PlanBlockExternalRef> findByExternalUid(String externalUid);

    /** 실행기가 보내는 동안 쥐는 잠금({@link OutboundOpExecutor}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PlanBlockExternalRef> findLockedById(UUID id);
}
