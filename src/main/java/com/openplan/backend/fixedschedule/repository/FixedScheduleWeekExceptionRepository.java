package com.openplan.backend.fixedschedule.repository;

import com.openplan.backend.fixedschedule.domain.FixedScheduleWeekException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 고정 일정 주차 예외 저장소 (PLAN-33/34). 소유 스코프 판정은 서비스가 부모 고정 일정으로 먼저 확인한다.
 */
public interface FixedScheduleWeekExceptionRepository extends JpaRepository<FixedScheduleWeekException, UUID> {

    /** 멱등 판정(PLAN-33) — 이미 그 주 예외가 있으면 200으로 수렴. DB UNIQUE가 경합 백스톱. */
    boolean existsByFixedScheduleIdAndWeekStartDate(UUID fixedScheduleId, LocalDate weekStartDate);

    /** 멱등 삭제(PLAN-34) — 예외 부재여도 무해(0건 삭제). @return 삭제된 행 수. */
    long deleteByFixedScheduleIdAndWeekStartDate(UUID fixedScheduleId, LocalDate weekStartDate);
}
