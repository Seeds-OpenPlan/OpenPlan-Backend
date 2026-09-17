package com.openplan.backend.externalcalendar.outbound;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScheduleExternalRefRepository extends JpaRepository<ScheduleExternalRef, UUID> {

    Optional<ScheduleExternalRef> findByExternalUid(String externalUid);

    java.util.List<ScheduleExternalRef> findByConnectionId(java.util.UUID connectionId);
}
