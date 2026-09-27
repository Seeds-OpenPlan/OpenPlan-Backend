package com.openplan.backend.externalcalendar.outbound;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlanBlockExternalRefRepository extends JpaRepository<PlanBlockExternalRef, UUID> {

    List<PlanBlockExternalRef> findByWeeklyPlanId(UUID weeklyPlanId);

    List<PlanBlockExternalRef> findByUserId(UUID userId);

    Optional<PlanBlockExternalRef> findByExternalUid(String externalUid);
}
