package com.openplan.backend.rule.port;

import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ReplanOptionResult;

import java.util.List;

/**
 * SS-07~09 재계획 대안 생성 (RB-PLAN-03·04·05) — 3전략. 소비자는 구현이 아니라 이 계약에만 의존한다.
 *
 * <p>동일 snapshot → 동일 결과(P1, 결정적). 반환은 대안 3종(MINIMAL_CHANGE·DEADLINE_FIRST·WORKLOAD_BALANCE);
 * 기준선(KEEP_CURRENT)은 현재 배치 그대로라 엔진이 만들지 않는다(라우트가 응답에 별도로 싣는다). NFR-029: ≤ 5초.
 *
 * <p>재배치 대상은 <b>TASK 블록만</b>이다. SCHEDULE 블록·고정일정은 시각 고정 제약이라 못 옮긴다(snapshot에서
 * busy로 취급). 태스크 사실은 {@code snapshot.taskFacts()}에 있어야 한다.
 */
public interface PlanReplanPort {

    List<ReplanOptionResult> generate(PlanSnapshot snapshot);
}
