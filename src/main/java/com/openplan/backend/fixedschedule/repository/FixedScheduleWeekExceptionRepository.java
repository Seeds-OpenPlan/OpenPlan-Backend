package com.openplan.backend.fixedschedule.repository;

import com.openplan.backend.fixedschedule.domain.FixedScheduleWeekException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 고정 일정 주차 예외 저장소 (PLAN-33/34). 소유 스코프 판정은 서비스가 부모 고정 일정으로 먼저 확인한다.
 */
public interface FixedScheduleWeekExceptionRepository extends JpaRepository<FixedScheduleWeekException, UUID> {

    /**
     * 멱등 생성(PLAN-33) — {@code ON CONFLICT DO NOTHING}. 이미 그 주 예외가 있으면 아무것도 안 하고 0을 반환한다.
     *
     * <p><b>saveAndFlush + catch(DataIntegrityViolationException) 대신 이 방식을 쓰는 이유</b>:
     * 유니크 위반 예외가 트랜잭션 안에서 발생하면 Spring이 트랜잭션을 rollback-only로 마킹해, catch로 삼켜도
     * 커밋 시점에 {@code UnexpectedRollbackException}(500)이 난다. {@code ON CONFLICT}는 예외 자체를 발생시키지
     * 않으므로 rollback-only 마킹이 없고, 동시 요청도 안전하게 멱등 수렴한다(리뷰 반영).
     *
     * @return 삽입된 행 수 — 1이면 신규(201), 0이면 이미 존재(200).
     */
    @Modifying
    @Query(value = """
            INSERT INTO fixed_schedule_week_exceptions (exception_id, fixed_schedule_id, week_start_date)
            VALUES (:id, :fixedScheduleId, :weekStartDate)
            ON CONFLICT (fixed_schedule_id, week_start_date) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("fixedScheduleId") UUID fixedScheduleId,
                       @Param("weekStartDate") LocalDate weekStartDate);

    /** 멱등 삭제(PLAN-34) — 예외 부재여도 무해(0건 삭제). @return 삭제된 행 수. */
    long deleteByFixedScheduleIdAndWeekStartDate(UUID fixedScheduleId, LocalDate weekStartDate);

    /**
     * 한 고정 일정의 예외 주차 전량 (FIX-07 충돌 미리보기). 미리보기는 여러 주를 훑으므로 주마다
     * 존재 여부를 묻지 않고 한 번에 읽어 집합으로 쓴다(주 수만큼 쿼리가 늘지 않게).
     */
    @Query("select e.weekStartDate from FixedScheduleWeekException e where e.fixedScheduleId = :fixedScheduleId")
    List<LocalDate> findWeekStartDatesByFixedScheduleId(@Param("fixedScheduleId") UUID fixedScheduleId);

    /**
     * 주어진 고정 일정 후보들 중 그 주({@code weekStartDate}) 예외가 있는 id만 (이슈 #90 — 주간 화면
     * {@code activeThisWeek} 판정). <b>한 번에 조회</b> — 고정 일정 수만큼 "이 주 예외 있나" 쿼리를
     * 반복하면 N+1이 되므로, 후보 id 목록을 통째로 넘겨 한 쿼리로 집합을 받는다
     * ({@link #findWeekStartDatesByFixedScheduleId}와 같은 "주 수만큼 쿼리 금지" 원칙을 id 축으로 적용).
     *
     * @param fixedScheduleIds 호출자가 이미 사용자 스코프로 조회한 후보(보통 {@code findOverlappingWeek}
     *                         결과) — 빈 목록이면 호출하지 않는다(IN () 은 방언에 따라 오류).
     */
    @Query("select e.fixedScheduleId from FixedScheduleWeekException e "
            + "where e.weekStartDate = :weekStartDate and e.fixedScheduleId in :fixedScheduleIds")
    List<UUID> findFixedScheduleIdsWithExceptionInWeek(@Param("weekStartDate") LocalDate weekStartDate,
                                                        @Param("fixedScheduleIds") List<UUID> fixedScheduleIds);
}
