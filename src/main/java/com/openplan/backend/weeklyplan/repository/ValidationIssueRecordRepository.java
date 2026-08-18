package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.ValidationIssueRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 검증 이슈 저장소 (ST-B2-09). 소유는 소속 weekly_plan(=user_id 스코프)으로 판정 — 서비스가 계획을 선판정한다.
 * 검증 재실행은 판정을 전면 교체하므로(스냅샷 = 그 시점 전량), 저장 전 해당 계획 이슈를 지우고 새로 넣는다.
 */
public interface ValidationIssueRecordRepository extends JpaRepository<ValidationIssueRecord, UUID> {

    /** 재검증 전 기존 판정 제거(전면 교체). @return 삭제 행 수. */
    long deleteByWeeklyPlanId(UUID weeklyPlanId);
}
