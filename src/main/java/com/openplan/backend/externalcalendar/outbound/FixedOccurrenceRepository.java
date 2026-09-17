package com.openplan.backend.externalcalendar.outbound;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FixedOccurrenceRepository extends JpaRepository<FixedOccurrence, UUID> {

    List<FixedOccurrence> findByUserId(UUID userId);

    Optional<FixedOccurrence> findByExternalUid(String externalUid);
}
