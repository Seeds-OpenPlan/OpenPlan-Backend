package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 주간 계획 저장소 (ST-B2-07). 전 쿼리는 user_id 스코프(소유자 격리·404 은닉).
 */
public interface WeeklyPlanRepository extends JpaRepository<WeeklyPlan, UUID> {

    /** 주차별 단건 조회 — GET(부재 → 200 빈 응답)·POST get-or-create 공용. UNIQUE(user_id,week_start_date). */
    Optional<WeeklyPlan> findByUserIdAndWeekStartDate(UUID userId, LocalDate weekStartDate);

    /** 소유자 스코프 단건(planId) — 블록 쓰기 공용(ST-B2-08). 부재·타인 → 404 E-COM-004. */
    Optional<WeeklyPlan> findByIdAndUserId(UUID id, UUID userId);
}
