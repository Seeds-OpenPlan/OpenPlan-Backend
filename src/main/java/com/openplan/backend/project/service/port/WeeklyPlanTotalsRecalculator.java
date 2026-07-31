package com.openplan.backend.project.service.port;

import java.util.List;
import java.util.UUID;

/**
 * 경계 계약 B-4 — 프로젝트 삭제 tx는 영향받은 주차의 {@code total_planned_minutes}(반정규화 캐시) 재계산 책임을 진다.
 *
 * <p>재계산 로직의 소유는 PLAN 도메인이며, 본 port가 그 인계 계약이다(PLAN 스토리 킥오프 시 구현 이관, 인터페이스 불변).
 * project 도메인은 이 port로만 접촉하고 weekly_plans/plan_blocks 테이블을 직접 다루지 않는다.
 *
 * <p>계약: ① 두 메서드 모두 호출자 tx에 참여(REQUIRED, 같은 tx — AC-09-6) ② {@link #recalculate}는 멱등
 * (남은 블록 절대값 재계산 — 증분 감산 아님) ③ 빈 목록 허용(no-op).
 */
public interface WeeklyPlanTotalsRecalculator {

    /**
     * 삭제 <b>전</b> 호출 — 해당 프로젝트의 태스크에 걸린 plan_block들이 속한 주차 id를 수집한다.
     * cascade 삭제 후에는 plan_blocks가 사라져 영향 주차를 알 수 없으므로 반드시 삭제 전에 수집한다.
     */
    List<UUID> captureAffectedWeeklyPlanIds(UUID projectId);

    /**
     * 태스크 삭제 <b>전</b> 호출 — 해당 <b>태스크</b>의 plan_block들이 속한 주차 id를 수집한다(taskId 스코프).
     * ST-B2-03 태스크 삭제(TB-4)용 추가 계약 — projectId 과수집을 피해 삭제 대상 태스크가 걸린 주차만 재계산한다.
     * (ADR-B2-03-006 — 추가 전용, 기존 메서드·호출처 무변경. 블록 단위 연산이 필요한 ST-B2-08 선행 자산.)
     */
    List<UUID> captureAffectedWeeklyPlanIdsByTask(UUID taskId);

    /** 삭제(+flush) 후, 같은 tx에서 남은 블록 기준으로 각 주차 total_planned_minutes를 재계산한다. */
    void recalculate(List<UUID> weeklyPlanIds);
}
