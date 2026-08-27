package com.openplan.backend.schedule.repository;

import com.openplan.backend.schedule.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 일정 저장소 (PLAN-08·17). 소유는 {@code user_id} 스코프(소유자 격리·404 은닉).
 */
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {

    /** 소유자 스코프 단건 — 편집(PLAN-17) 공용. 부재·타인 → empty → 404 E-COM-004(존재 은닉). */
    Optional<Schedule> findByIdAndUserId(UUID id, UUID userId);
}
