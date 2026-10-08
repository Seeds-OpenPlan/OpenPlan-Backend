package com.openplan.backend.fixedschedule.repository;

import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 고정 일정 저장소 (FIX-04~09). 전 쿼리는 user_id 스코프(소유자 격리·404 은닉).
 * 목록 정렬은 요일 → 시작 시각 고정(ix_fixed_schedules_user_status 활용).
 */
public interface FixedScheduleRepository extends JpaRepository<FixedSchedule, UUID> {

    /** 목록(FIX-04) — 전체. weekday ASC, start_time ASC. */
    List<FixedSchedule> findByUserIdOrderByWeekdayAscStartTimeAsc(UUID userId);

    /** 목록(FIX-04) — status 필터. weekday ASC, start_time ASC. */
    List<FixedSchedule> findByUserIdAndStatusOrderByWeekdayAscStartTimeAsc(UUID userId, FixedScheduleStatus status);

    /** 소유자 스코프 단건 — 편집·삭제 공용. 부재·타인 → empty → 404 E-COM-004(존재 은닉). */
    Optional<FixedSchedule> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 그 주([weekStartDate, weekEndDate])에 걸치는 고정 일정 전량 (이슈 #90 — 주간 화면
     * {@code WeeklyPlanView.fixedSchedules}). <b>status 무관</b>(ACTIVE·INACTIVE 둘 다 포함) —
     * INACTIVE도 계약상 고스트 표시 대상이라 여기서 거르면 안 된다(그 주 유효 판정은 호출자가
     * {@code activeThisWeek}로 따로 매긴다).
     *
     * <p>겹침 판정은 <b>날짜 범위만</b> 본다(요일 무관) — {@code startDate}/{@code endDate}는 반복이
     * "언제부터 언제까지" 유효한지를 정하고, 요일은 그 범위 안에서 어느 날에 반복되는지를 정하는
     * 별개 축이다. null은 그 쪽으로 무기한(시작 전부터 또는 끝없이 계속)을 뜻해 항상 겹친다고 본다.
     *
     * <p><b>DB {@code ORDER BY}를 쓰지 않는다</b> — {@code weekday}는 {@code @Enumerated(EnumType.STRING)}
     * (VARCHAR)라 SQL {@code ORDER BY}는 알파벳순(FRI·MON·SAT…)이 되어 달력순(MON→SUN)과 다르다.
     * ({@code findByUserIdOrderByWeekdayAscStartTimeAsc}도 같은 결함이 있으나 이 메서드의 범위 밖이라
     * 그대로 둔다 — 별도 보고.) 호출자({@link com.openplan.backend.weeklyplan.service.FixedScheduleWeekAssembler})가
     * {@code Weekday.ordinal()} 기준으로 Java에서 재정렬한다({@code AvailabilityService.toView}와 동일 관례).
     */
    @Query("select fs from FixedSchedule fs where fs.userId = :userId "
            + "and (fs.startDate is null or fs.startDate <= :weekEndDate) "
            + "and (fs.endDate is null or fs.endDate >= :weekStartDate)")
    List<FixedSchedule> findOverlappingWeek(@Param("userId") UUID userId,
                                            @Param("weekStartDate") LocalDate weekStartDate,
                                            @Param("weekEndDate") LocalDate weekEndDate);
}
