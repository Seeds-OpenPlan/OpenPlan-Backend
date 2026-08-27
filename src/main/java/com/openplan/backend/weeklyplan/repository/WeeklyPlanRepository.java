package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
     * 저장된 주간 계획 (FIX-07 충돌 미리보기 — 정본 "저장된 주간 계획 대상"). 주차 오름차순으로
     * 응답 순서를 결정적으로 만든다. <b>과거 주를 정책적으로 빼지 않는다</b> — 정본이 범위를 좁히지 않았다.
     *
     * <p>경계 인자는 <b>정책이 아니라 순수 비용 절감</b>이다. V2는 후보 유효기간
     * ({@code effectiveFrom}/{@code To}) 밖 발생일을 스스로 0건 처리하므로, 그런 주는 읽어도 결과가
     * 같다 — 그럼에도 주마다 블록·가용시간·고정일정·태스크 조회 + 엔진 1회가 든다. 애초에 이슈가
     * 나올 수 없는 주를 쿼리 단계에서 뺀다.
     *
     * <p>경계 산식(한 주는 {@code [weekStartDate, weekStartDate+6]}을 덮는다):
     * <ul>
     *   <li>{@code fromBound = effectiveFrom - 6일} — 주 끝자락이 유효 시작일에 걸릴 수 있어 6일 뺀다</li>
     *   <li>{@code toBound = effectiveTo} — 주 시작이 유효 종료일을 넘으면 그 주엔 발생일이 없다</li>
     * </ul>
     * 양쪽 다 <b>과대 근사</b>라 이슈가 날 수 있는 주는 절대 빠지지 않는다(정확 판정은 엔진 몫).
     * 무기한 후보(둘 다 null)는 모든 주가 실제로 판정 대상이므로 필터가 걸리지 않는다 — 계약상 불가피한 비용이다.
     *
     * @param fromBound null이면 하한 없음
     * @param toBound   null이면 상한 없음
     */
    @Query("""
            select w from WeeklyPlan w
             where w.userId = :userId
               and (cast(:fromBound as date) is null or w.weekStartDate >= :fromBound)
               and (cast(:toBound   as date) is null or w.weekStartDate <= :toBound)
             order by w.weekStartDate asc
            """)
    List<WeeklyPlan> findForConflictPreview(@Param("userId") UUID userId,
                                            @Param("fromBound") LocalDate fromBound,
                                            @Param("toBound") LocalDate toBound);

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

    /**
     * 주차 계획 get-or-create의 원자적 생성 (PLAN-20 주차 이동) — {@code INSERT ... ON CONFLICT DO NOTHING}.
     *
     * <p><b>동시 요청 경합을 예외 없이 흡수한다.</b> {@code saveAndFlush} + {@code catch(DataIntegrityViolationException)}는
     * 위반이 트랜잭션을 rollback-only로 마킹해, 잡고 재조회해도 커밋 시 {@code UnexpectedRollbackException}(500)이 난다
     * (오민아 리뷰 · 주차예외 슬라이스에서 확인된 함정). ON CONFLICT는 위반 자체를 만들지 않아 그 창을 원천 차단한다.
     * 신규 계획이라 total=0·status=DRAFT(DB DEFAULT). 호출자는 뒤이어 재조회로 승자 행을 얻는다.
     *
     * @return 삽입 1 / 이미 존재 0 (호출자는 값과 무관하게 재조회)
     */
    @Modifying
    @Query(value = """
            INSERT INTO weekly_plans (weekly_plan_id, user_id, week_start_date, week_end_date, created_at)
            VALUES (:id, :userId, :weekStartDate, :weekEndDate, :now)
            ON CONFLICT (user_id, week_start_date) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
                       @Param("weekStartDate") LocalDate weekStartDate,
                       @Param("weekEndDate") LocalDate weekEndDate, @Param("now") Instant now);
}
