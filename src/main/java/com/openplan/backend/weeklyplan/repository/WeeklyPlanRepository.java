package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 주간 계획 저장소 (ST-B2-07). 전 쿼리는 user_id 스코프(소유자 격리·404 은닉).
 */
public interface WeeklyPlanRepository extends JpaRepository<WeeklyPlan, UUID> {

    /** 중복 주차 사전 판정 — true면 서비스가 409 E-PLAN-001로 라우팅(DB UNIQUE(user_id,week_start_date) 백스톱). */
    boolean existsByUserIdAndWeekStartDate(UUID userId, LocalDate weekStartDate);

    /** 주차별 단건 조회(PLAN-02 주차 이동). 부재 → 404 E-COM-004. */
    Optional<WeeklyPlan> findByUserIdAndWeekStartDate(UUID userId, LocalDate weekStartDate);

    /**
     * 배치 블록 수(PLAN-01 요약). plan_blocks는 weekly-plan 도메인 소유 테이블이나 엔티티는 ST-B2-08 —
     * 그전까진 native count로 집계한다(요약 전용 읽기).
     */
    @Query(value = "SELECT COUNT(*) FROM plan_blocks WHERE weekly_plan_id = :planId", nativeQuery = true)
    long countBlocks(@Param("planId") UUID planId);
}
