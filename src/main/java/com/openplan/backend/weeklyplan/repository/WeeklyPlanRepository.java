package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * 확정 게이트 (ST-B2-09 / PLAN-03) — {@code DRAFT}일 때만 원자적으로 {@code CONFIRMED}로 전이한다.
     *
     * <p><b>더블클릭·동시 요청 방어의 핵심이다.</b> 엔티티 {@code plan.confirm()}(버전 증가 dirty update)로 하면
     * 동시 두 요청이 각자 버전을 올려 하나가 낙관락 충돌(409 E-COM-006)로 튄다. 이 조건부 UPDATE는
     * <b>DB 행 잠금으로 직렬화</b>되어 한 요청만 1행을 바꾸고 나머지는 0행을 받는다 — 예외 없이 멱등 수렴한다.
     * {@code @Version}을 명시적으로 올려 이후 일반 편집의 낙관락 정합을 유지한다.
     * {@code clearAutomatically}로 영속 컨텍스트를 비워, 호출 후 재조회가 갱신된 상태를 보게 한다.
     *
     * @return 1이면 이 요청이 확정에 성공, 0이면 이미 확정됨(동시 요청이 먼저 수행)
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE WeeklyPlan w
               SET w.status = com.openplan.backend.weeklyplan.domain.WeeklyPlanStatus.CONFIRMED,
                   w.confirmedAt = :now,
                   w.version = w.version + 1
             WHERE w.id = :planId
               AND w.status = com.openplan.backend.weeklyplan.domain.WeeklyPlanStatus.DRAFT
            """)
    int confirmIfDraft(@Param("planId") UUID planId, @Param("now") Instant now);
}
