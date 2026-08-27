package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.ReplanOption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 재계획 대안 저장소 (SS-07~09). 소유는 소속 weekly_plan(=user_id 스코프)으로 판정 — 서비스가 계획을 선판정한다.
 */
public interface ReplanOptionRepository extends JpaRepository<ReplanOption, UUID> {

    /** 그 주 대안 목록 — 생성 순(created_at) 재조회(GET)·재생성 전 정리 판단 공용. */
    List<ReplanOption> findByWeeklyPlanIdOrderByCreatedAtAsc(UUID weeklyPlanId);

    /** 재생성 = 전면 교체: 기존 대안 제거 후 새로 삽입. @return 삭제 행 수. */
    long deleteByWeeklyPlanId(UUID weeklyPlanId);

    /**
     * 소유자 스코프로 <b>실제 선택된</b> 대안을 기간 조회 — SS-14 기본값 제안의 유일한 근거다.
     * 선택되지 않은 대안은 "보여준 것"일 뿐이라 선호로 셀 수 없다.
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT o FROM ReplanOption o
             WHERE o.selected = true
               AND o.selectedAt >= :from AND o.selectedAt < :to
               AND o.weeklyPlanId IN (SELECT wp.id FROM WeeklyPlan wp WHERE wp.userId = :userId)
            """)
    List<ReplanOption> findSelectedByUserIdAndSelectedAtRange(
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to);

    /** 소유자 스코프 단건(optionId) — 적용(PLAN-29) 공용. weekly_plans.user_id로 소유 판정. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT o FROM ReplanOption o
             WHERE o.id = :optionId
               AND o.weeklyPlanId IN (SELECT wp.id FROM WeeklyPlan wp WHERE wp.userId = :userId)
            """)
    Optional<ReplanOption> findByIdAndUserId(
            @org.springframework.data.repository.query.Param("optionId") UUID optionId,
            @org.springframework.data.repository.query.Param("userId") UUID userId);
}
