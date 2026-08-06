package com.openplan.backend.schedule.repository;

import com.openplan.backend.schedule.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 일정 저장소 (PLAN-08). 이번 스토리(ST-B2-08)는 SCHEDULE 블록 배치 시 {@code save}만 사용한다.
 * 편집·삭제·목록은 후속 스토리에서 추가.
 */
public interface ScheduleRepository extends JpaRepository<Schedule, UUID> {
}
