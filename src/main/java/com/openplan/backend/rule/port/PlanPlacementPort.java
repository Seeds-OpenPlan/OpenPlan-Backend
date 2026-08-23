package com.openplan.backend.rule.port;

import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;

import java.util.List;
import java.util.UUID;

/**
 * SS-05 자동 배치 제안 (RB-PLAN-01) — first-fit. 소비자는 구현이 아니라 이 계약에만 의존한다({@link PlanValidationPort}와 동일 관례).
 *
 * <p>동일 snapshot·taskIds → 동일 result(P1, 결정적). {@code taskIds}의 태스크 사실은 {@code snapshot.taskFacts()}에
 * 들어 있어야 한다(라우트가 후보 태스크를 스냅샷에 포함시켜 넘긴다). NFR-029: ≤ 5초.
 */
public interface PlanPlacementPort {

    PlacementResult propose(PlanSnapshot snapshot, List<UUID> taskIds);
}
