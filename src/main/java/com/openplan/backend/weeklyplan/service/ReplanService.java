package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ProposedPlacement;
import com.openplan.backend.rule.model.ReplanOptionResult;
import com.openplan.backend.rule.model.ReplanStrategy;
import com.openplan.backend.rule.port.PlanReplanPort;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.PlanBlockType;
import com.openplan.backend.weeklyplan.domain.ReplanOption;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.GenerateReplanResponse;
import com.openplan.backend.weeklyplan.dto.ReplanOptionResponse;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.ReplanOptionRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 재계획 대안 생성 (SS-07~09 / RB-PLAN-03·04·05). <b>규칙(3전략)은 만들지 않는다</b> — 스냅샷을 조립해
 * {@link PlanReplanPort}(엔진)에 넘기고 결과를 저장·응답으로 매핑한다(엔진 직접 참조 금지, 포트 경유).
 *
 * <p>재생성은 그 주 기존 대안을 <b>전면 교체</b>한다(최신 대안만 의미). 기준선(KEEP_CURRENT)은 저장하지 않고
 * 응답에만 싣는다(현재 TASK 배치 그대로).
 */
@Service
public class ReplanService {

    private final WeeklyPlanRepository weeklyPlanRepository;
    private final PlanBlockRepository planBlockRepository;
    private final ReplanOptionRepository replanOptionRepository;
    private final PlanSnapshotAssembler assembler;
    private final PlanReplanPort replanPort;
    private final UserClock clock;

    public ReplanService(WeeklyPlanRepository weeklyPlanRepository, PlanBlockRepository planBlockRepository,
                         ReplanOptionRepository replanOptionRepository, PlanSnapshotAssembler assembler,
                         PlanReplanPort replanPort, UserClock clock) {
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.planBlockRepository = planBlockRepository;
        this.replanOptionRepository = replanOptionRepository;
        this.assembler = assembler;
        this.replanPort = replanPort;
        this.clock = clock;
    }

    /**
     * 대안 생성 (generateReplanOptions). 스냅샷 조립 → 엔진 3전략 → 전면 교체 저장 → 기준선+대안 3종 반환. 404(부재·타인).
     */
    @Transactional
    public GenerateReplanResponse generate(UUID userId, UUID planId) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        List<PlanBlock> blocks = planBlockRepository.findByWeeklyPlanId(planId);
        List<BlockView> blockViews = blocks.stream().map(this::toBlockView).toList();

        PlanSnapshot snapshot = assembler.assemble(userId, plan.getWeekStartDate(), blockViews, Map.of());
        List<ReplanOptionResult> results = replanPort.generate(snapshot);

        // 전면 교체 저장
        replanOptionRepository.deleteByWeeklyPlanId(planId);
        List<ReplanOptionResponse> options = new ArrayList<>();
        for (ReplanOptionResult r : results) {
            List<ReplanOption.StoredBlock> stored = r.placements().stream()
                    .map(p -> new ReplanOption.StoredBlock(p.taskId(), p.startAt(), p.endAt()))
                    .toList();
            ReplanOption entity = ReplanOption.create(planId, r.strategy(),
                    changeSummary(r), recommendationReason(r.strategy()), stored, clock.now());
            replanOptionRepository.save(entity);
            options.add(toResponse(entity, r.placements()));
        }

        return new GenerateReplanResponse(baseline(blocks), options);
    }

    // ─────────────────────────────────────────── 매핑·문구

    /** 기준선(KEEP_CURRENT) — 저장 안 함(id=null). 현재 TASK 블록을 그대로 제안으로 싣는다. */
    private ReplanOptionResponse baseline(List<PlanBlock> blocks) {
        List<ReplanOptionResponse.ProposedBlock> current = blocks.stream()
                .filter(b -> b.getBlockType() == PlanBlockType.TASK)
                .map(b -> new ReplanOptionResponse.ProposedBlock("TASK", b.getTaskId(), b.getStartAt(), b.getEndAt()))
                .toList();
        return new ReplanOptionResponse(null, "KEEP_CURRENT",
                "현재 계획을 그대로 유지합니다.", null, null, current, false);
    }

    private ReplanOptionResponse toResponse(ReplanOption entity, List<ProposedPlacement> placements) {
        List<ReplanOptionResponse.ProposedBlock> proposed = placements.stream()
                .map(p -> new ReplanOptionResponse.ProposedBlock("TASK", p.taskId(), p.startAt(), p.endAt()))
                .toList();
        return new ReplanOptionResponse(entity.getId(), entity.getStrategyType().name(),
                entity.getChangeSummary(), entity.getRecommendationReason(), entity.getScore(),
                proposed, entity.isSelected());
    }

    /** 변경 규모 요약 — 이동된 TASK 수 기준(P4 규칙 문구). */
    private String changeSummary(ReplanOptionResult r) {
        int changed = r.changedTaskIds().size();
        int unplaced = r.unplacedTaskIds().size();
        if (changed == 0 && unplaced == 0) {
            return "변경 없이 유지됩니다.";
        }
        StringBuilder sb = new StringBuilder(changed + "개 항목을 이동합니다.");
        if (unplaced > 0) {
            sb.append(" 배치하지 못한 항목 ").append(unplaced).append("개.");
        }
        return sb.toString();
    }

    /** 전략별 규칙 근거(C-3·P4 — AI 표현 없음). */
    private String recommendationReason(ReplanStrategy strategy) {
        return switch (strategy) {
            case MINIMAL_CHANGE -> "충돌한 항목만 인접 가용 시간으로 옮겨 기존 계획을 최대한 유지합니다.";
            case DEADLINE_FIRST -> "마감일이 가까운 항목을 앞쪽 가용 시간에 우선 배치합니다.";
            case WORKLOAD_BALANCE -> "가용 시간을 초과한 날의 항목을 여유 있는 날로 분산합니다.";
        };
    }

    private BlockView toBlockView(PlanBlock b) {
        return new BlockView(b.getId(), BlockType.valueOf(b.getBlockType().name()),
                b.getTaskId(), b.getScheduleId(), b.getStartAt(), b.getEndAt());
    }
}
