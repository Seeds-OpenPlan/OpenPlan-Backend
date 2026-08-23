package com.openplan.backend.ai;

import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.port.PlanPlacementPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * AI 초안을 {@link PlanPlacementPort} 자리에 앉히는 어댑터 (SS-05 / RB-PLAN-01).
 *
 * <p><b>왜 새 엔드포인트가 아니라 이 자리인가.</b> AI 계약 D-2 가 규칙 first-fit 과 AI 초안의 입출력을
 * 일부러 동형으로 설계했다 — "둘의 입출력이 같으면 Spring 입장에서 AI 와 규칙을 같은 자리에서 갈아끼울 수
 * 있다". 아키텍처 S5 도 같은 말을 한다("같은 port 의 다른 구현"). 그래서 프론트는 바뀌지 않고
 * {@code POST /weekly-plans/&#123;planId&#125;/auto-placements} 를 그대로 부른다.
 *
 * <p><b>폴백이 계약의 일부다.</b> AI 계약 §4: "AI 가 죽으면 데모도 죽는다가 되지 않게, Spring 은 항상
 * 규칙 경로를 갖고 있어야 한다." 그래서 이 어댑터는 AI 를 <b>대체</b>가 아니라 <b>앞단</b>으로 둔다 —
 * 실패하면 조용히 규칙으로 내려간다. 사용자는 초안을 못 받는 일이 없다.
 *
 * <p><b>판정은 하지 않는다.</b> AI 가 겹침·마감 위반을 낼 수 있고 그걸 잡는 것은 규칙 엔진의 일이다
 * (계약 §4). 이 어댑터는 제안을 옮겨 담을 뿐이고, 검증은 라우트가 {@code PlanValidationPort} 로 한다.
 */
public class AiPlacementAdapter implements PlanPlacementPort {

    private static final Logger log = LoggerFactory.getLogger(AiPlacementAdapter.class);

    private final AiPlanDraftClient client;
    private final PlanPlacementPort ruleFallback;

    public AiPlacementAdapter(AiPlanDraftClient client, PlanPlacementPort ruleFallback) {
        this.client = client;
        this.ruleFallback = ruleFallback;
    }

    @Override
    public PlacementResult propose(PlanSnapshot snapshot, List<UUID> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            // 배치할 것이 없으면 AI 를 부르지 않는다 — 한도·지연을 빈 요청에 쓰지 않는다.
            return ruleFallback.propose(snapshot, taskIds);
        }
        try {
            AiPlanDraftClient.DraftResponse draft = client.draft(snapshot, taskIds);
            return toResult(draft, taskIds);
        } catch (AiPlanDraftClient.AiUnavailableException ex) {
            // 계약 §4 실패 표: 502·503·504·타임아웃 모두 "규칙 폴백". 사용자에게는 아무 일도 없었던 것처럼 보인다.
            log.warn("AI 초안 실패 — 규칙 first-fit 으로 대체한다: {}", ex.getMessage());
            // 스택은 debug 에만 — 폴백은 정상 경로라 매번 스택을 찍으면 운영 로그가 묻힌다.
            log.debug("AI 초안 실패 상세", ex);
            return ruleFallback.propose(snapshot, taskIds);
        }
    }

    /**
     * AI 응답 → 포트 계약. <b>요청하지 않은 태스크는 버린다.</b>
     *
     * <p>AI 가 {@code tasksToPlace} 밖의 태스크를 배치해 보낼 수 있는데, 그대로 통과시키면 사용자가
     * 건드리지 않기로 한 블록이 옮겨진다. 대상 선정은 Spring 이 한다는 계약 §7 #3 을 응답 쪽에서도 지킨다.
     * {@code SCHEDULE} 타입도 버린다 — 자동 배치는 미배치 <b>태스크</b>만 대상이다.
     */
    private PlacementResult toResult(AiPlanDraftClient.DraftResponse draft, List<UUID> requested) {
        List<ProposedPlacement> placements = draft.proposedBlocks().stream()
                .filter(b -> b.taskId() != null && requested.contains(b.taskId()))
                .filter(b -> !"SCHEDULE".equals(b.type()))
                .filter(b -> b.startAt() != null && b.endAt() != null && b.startAt().isBefore(b.endAt()))
                .map(b -> new ProposedPlacement(b.taskId(), b.startAt(), b.endAt()))
                .toList();

        int dropped = draft.proposedBlocks().size() - placements.size();
        if (dropped > 0) {
            // 조용히 버리지 않는다 — 계약 위반은 AI 쪽 수정 대상이라 흔적이 남아야 한다.
            log.warn("AI 제안 {}건을 버렸다 — 요청 밖 태스크·SCHEDULE·시각 역전", dropped);
        }

        // 배치되지 않은 것 = 요청했는데 제안에 없는 것. AI 의 unplacedTaskIds 를 그대로 믿지 않고
        // 실제 배치 결과에서 역산한다 — 위에서 버린 제안이 있으면 AI 의 목록과 어긋나기 때문이다.
        List<UUID> placedIds = placements.stream().map(ProposedPlacement::taskId).distinct().toList();
        List<UUID> unplaced = requested.stream()
                .filter(id -> !placedIds.contains(id))
                .filter(Objects::nonNull)
                .toList();

        return new PlacementResult(placements, unplaced, draft.reason());
    }
}
