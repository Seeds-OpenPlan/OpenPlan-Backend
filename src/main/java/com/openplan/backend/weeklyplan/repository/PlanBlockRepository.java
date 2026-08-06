package com.openplan.backend.weeklyplan.repository;

import com.openplan.backend.weeklyplan.domain.PlanBlock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 계획 블록 저장소 (ST-B2-08). 소유는 소속 weekly_plan(=user_id 스코프)으로 판정 — 서비스가 계획을 선판정한다.
 *
 * <p>본 슬라이스(배치)는 {@code save}만 사용한다. 블록 해제·이동·목록은 후속 슬라이스에서 추가.
 */
public interface PlanBlockRepository extends JpaRepository<PlanBlock, UUID> {
}
