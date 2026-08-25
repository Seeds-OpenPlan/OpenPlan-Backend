package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.port.PlanPlacementPort;
import com.openplan.backend.weeklyplan.dto.AutoPlacementRequest;
import com.openplan.backend.weeklyplan.dto.PlacementProposalResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 자동 배치 제안 (SS-05 / RB-PLAN-01). <b>규칙(first-fit)은 만들지 않는다</b> — 후보·스냅샷을 조립해
 * {@link PlanPlacementPort}(엔진)에 넘기고 결과를 응답으로 매핑한다(엔진 직접 참조 금지, 포트 경유 — ST-B2-09와 동일).
 *
 * <p><b>저장하지 않는다</b>(C-2) — 제안일 뿐이며, 적용은 사용자가 {@code block-batches}로 한다.
 *
 * <p><b>이 클래스엔 {@code @Transactional}을 달지 않는다.</b> 읽기 전용 tx는 {@link AutoPlacementSnapshotReader}
 * 가 스냅샷을 조립하는 동안만 열려 있고, {@link PlanPlacementPort#propose}(AI 배선이면 최대 20초 동기 HTTP)는
 * 그 tx가 끝난 뒤 여기서 부른다 — 커넥션을 쥔 채 네트워크를 기다리면 동시 요청 수만큼 DB 커넥션 풀이 고갈될
 * 수 있어서다(PR #39 리뷰 지적). 자세한 이유는 {@link AutoPlacementSnapshotReader}의 클래스 주석 참고.
 */
@Service
public class AutoPlacementService {

    /** 정렬 규칙 설명(P4 — 규칙 기반 문구). 정본 PlacementProposal.reason. */
    private static final String REASON = "우선순위·마감일·예상시간 순으로 가용 시간에 채웠습니다(first-fit).";

    private final AutoPlacementSnapshotReader snapshotReader;
    private final PlanPlacementPort placementPort;

    public AutoPlacementService(AutoPlacementSnapshotReader snapshotReader, PlanPlacementPort placementPort) {
        this.snapshotReader = snapshotReader;
        this.placementPort = placementPort;
    }

    /**
     * 자동 배치 제안 (proposeAutoPlacement). 스냅샷 조립(읽기 tx, {@link AutoPlacementSnapshotReader})
     * → tx 밖에서 엔진 호출 → PlacementProposal 반환. 계획 부재·타인 → 404(스냅샷 조립 단계에서 던져진다).
     */
    public PlacementProposalResponse propose(UUID userId, UUID planId, AutoPlacementRequest request) {
        AutoPlacementSnapshotReader.CandidateSnapshot read = snapshotReader.read(userId, planId, request);
        PlacementResult result = placementPort.propose(read.snapshot(), read.candidates());

        List<PlacementProposalResponse.ProposedBlock> proposed = result.placements().stream()
                .map(this::toProposedBlock).toList();
        // 제안기가 문구를 실어 보냈으면 그것을 쓴다(AI 경로 — 매번 근거가 다르다). 규칙 경로는 비워 오므로
        // 고정 문구로 대체한다. 여기서 갈리지 않으면 AI 가 만든 초안에 first-fit 설명이 붙는다.
        String reason = result.hasReason() ? result.reason() : REASON;
        return new PlacementProposalResponse(proposed, result.unplacedTaskIds(), reason);
    }

    private PlacementProposalResponse.ProposedBlock toProposedBlock(ProposedPlacement p) {
        return new PlacementProposalResponse.ProposedBlock("TASK", p.taskId(), p.startAt(), p.endAt());
    }
}
