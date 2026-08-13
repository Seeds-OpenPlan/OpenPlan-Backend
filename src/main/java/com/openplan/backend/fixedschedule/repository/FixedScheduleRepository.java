package com.openplan.backend.fixedschedule.repository;

import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
