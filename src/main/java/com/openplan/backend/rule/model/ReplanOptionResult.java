package com.openplan.backend.rule.model;

import java.util.List;
import java.util.UUID;

/**
 * 재계획 대안 1건 (SS-07~09) — 엔진의 순수 출력. 한 전략이 만든 TASK 재배치안.
 *
 * <p>{@code placements} = 재배치된 TASK 위치, {@code unplacedTaskIds} = 자리 없어 못 넣은 TASK,
 * {@code changedTaskIds} = 현재 배치 대비 시각이 바뀐 TASK(변경 규모 요약·score 재료). 저장·문구(reason)는
 * 라우트 몫이라 여기 없다(순수 엔진 밖 관심사).
 */
public record ReplanOptionResult(
        ReplanStrategy strategy,
        List<ProposedPlacement> placements,
        List<UUID> unplacedTaskIds,
        List<UUID> changedTaskIds) {
}
