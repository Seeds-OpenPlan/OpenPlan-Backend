package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlacementResult;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.port.PlanPlacementPort;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.AutoPlacementRequest;
import com.openplan.backend.weeklyplan.dto.PlacementProposalResponse;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 자동 배치 제안 (SS-05 / RB-PLAN-01). <b>규칙(first-fit)은 만들지 않는다</b> — 후보·스냅샷을 조립해
 * {@link PlanPlacementPort}(엔진)에 넘기고 결과를 응답으로 매핑한다(엔진 직접 참조 금지, 포트 경유 — ST-B2-09와 동일).
 *
 * <p><b>저장하지 않는다</b>(C-2) — 제안일 뿐이며, 적용은 사용자가 {@code block-batches}로 한다. 읽기 전용 tx.
 */
@Service
public class AutoPlacementService {

    /** 정렬 규칙 설명(P4 — 규칙 기반 문구). 정본 PlacementProposal.reason. */
    private static final String REASON = "우선순위·마감일·예상시간 순으로 가용 시간에 채웠습니다(first-fit).";

    private final WeeklyPlanRepository weeklyPlanRepository;
    private final PlanBlockRepository planBlockRepository;
    private final TaskRepository taskRepository;
    private final PlanSnapshotAssembler assembler;
    private final PlanPlacementPort placementPort;

    public AutoPlacementService(WeeklyPlanRepository weeklyPlanRepository,
                                PlanBlockRepository planBlockRepository,
                                TaskRepository taskRepository,
                                PlanSnapshotAssembler assembler,
                                PlanPlacementPort placementPort) {
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.planBlockRepository = planBlockRepository;
        this.taskRepository = taskRepository;
        this.assembler = assembler;
        this.placementPort = placementPort;
    }

    /**
     * 자동 배치 제안 (proposeAutoPlacement). 후보 = 요청 taskIds(미지정 시 미배치 전량, 사용자 미배치 집합으로 스코프).
     * 스냅샷 조립(후보 사실 포함) → 엔진 호출 → PlacementProposal 반환. 계획 부재·타인 → 404.
     */
    @Transactional(readOnly = true)
    public PlacementProposalResponse propose(UUID userId, UUID planId, AutoPlacementRequest request) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        List<UUID> candidates = resolveCandidates(userId, request);

        List<BlockView> blocks = planBlockRepository.findByWeeklyPlanId(planId).stream()
                .map(this::toBlockView).toList();

        PlanSnapshot snapshot = assembler.assemble(userId, plan.getWeekStartDate(), blocks, Map.of(), candidates);
        PlacementResult result = placementPort.propose(snapshot, candidates);

        List<PlacementProposalResponse.ProposedBlock> proposed = result.placements().stream()
                .map(this::toProposedBlock).toList();
        return new PlacementProposalResponse(proposed, result.unplacedTaskIds(), REASON);
    }

    /**
     * 후보 선정 — 사용자 미배치 태스크 전량을 기준으로, 요청에 taskIds가 있으면 그 교집합만(소유·미배치 스코프 강제).
     * 요청 id가 남의 것/이미 배치된 것이면 자연히 빠진다(존재 은닉·관대 처리).
     */
    private List<UUID> resolveCandidates(UUID userId, AutoPlacementRequest request) {
        List<UUID> unassigned = taskRepository.findUnassignedTaskIds(userId);
        if (request == null || request.taskIds() == null || request.taskIds().isEmpty()) {
            return unassigned;
        }
        java.util.Set<UUID> requested = new java.util.LinkedHashSet<>(request.taskIds());
        return unassigned.stream().filter(requested::contains).toList();
    }

    private BlockView toBlockView(PlanBlock b) {
        return new BlockView(b.getId(), BlockType.valueOf(b.getBlockType().name()),
                b.getTaskId(), b.getScheduleId(), b.getStartAt(), b.getEndAt());
    }

    private PlacementProposalResponse.ProposedBlock toProposedBlock(ProposedPlacement p) {
        return new PlacementProposalResponse.ProposedBlock("TASK", p.taskId(), p.startAt(), p.endAt());
    }
}
