package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.service.port.WeeklyPlanTotalsRecalculator;
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
import com.openplan.backend.weeklyplan.dto.PlanBlockResponse;
import com.openplan.backend.weeklyplan.dto.ReplanOptionResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanView;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.ReplanOptionRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
    private final WeeklyPlanTotalsRecalculator recalculator;
    private final UserClock clock;
    private final EntityManager entityManager;

    public ReplanService(WeeklyPlanRepository weeklyPlanRepository, PlanBlockRepository planBlockRepository,
                         ReplanOptionRepository replanOptionRepository, PlanSnapshotAssembler assembler,
                         PlanReplanPort replanPort, WeeklyPlanTotalsRecalculator recalculator, UserClock clock,
                         EntityManager entityManager) {
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.planBlockRepository = planBlockRepository;
        this.replanOptionRepository = replanOptionRepository;
        this.assembler = assembler;
        this.replanPort = replanPort;
        this.recalculator = recalculator;
        this.clock = clock;
        this.entityManager = entityManager;
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

    /**
     * 대안 재조회 (listReplanOptions / PLAN-29 비교 화면 새로고침). 저장된 대안 목록(생성 순)을 반환한다 —
     * 기준선(KEEP_CURRENT)은 저장되지 않으므로 목록에 없다(정본 응답 = ReplanOption 배열). 404(부재·타인). 읽기 tx.
     */
    @Transactional(readOnly = true)
    public List<ReplanOptionResponse> list(UUID userId, UUID planId) {
        weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404
        return replanOptionRepository.findByWeeklyPlanIdOrderByCreatedAtAsc(planId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 대안 선택+초안 반영 (applyReplanOption / PLAN-29). 저장된 {@code proposed_blocks}대로 현재 TASK 블록을
     * taskId 매칭해 목표 시각으로 이동하고, 이 대안을 선택 표시(is_selected·selected_at)한다.
     *
     * <p><b>초안 반영이며 확정이 아니다</b>(P2) — status는 DRAFT로 두되, 확정이었다면 편집 재개로 DRAFT 복귀.
     * 반영 후 최신 {@link WeeklyPlanView} 반환. 대안 부재·타인 → 404.
     */
    @Transactional
    public WeeklyPlanView apply(UUID userId, UUID optionId) {
        ReplanOption option = replanOptionRepository.findByIdAndUserId(optionId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (대안 부재·타인)
        UUID planId = option.getWeeklyPlanId();
        WeeklyPlan plan = weeklyPlanRepository.findById(planId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));

        // 현재 TASK 블록을 taskId별 큐로 — 같은 태스크 블록이 여럿이면 제안 순서대로 하나씩 소비.
        Map<UUID, Deque<PlanBlock>> blocksByTask = new HashMap<>();
        for (PlanBlock b : planBlockRepository.findByWeeklyPlanId(planId)) {
            if (b.getBlockType() == PlanBlockType.TASK && b.getTaskId() != null) {
                blocksByTask.computeIfAbsent(b.getTaskId(), t -> new ArrayDeque<>()).add(b);
            }
        }

        plan.reopenToDraftIfConfirmed(); // 편집(초안 반영) — 확정이면 DRAFT 복귀
        // 🔴 reschedule 이 @Modifying(clearAutomatically=true)라 컨텍스트를 비운다 —
        //    위 DRAFT 복귀(dirty)를 여기서 flush 하지 않으면 첫 reschedule 의 clear 로 버려진다.
        weeklyPlanRepository.flush();

        // 제안 위치대로 이동 — 매칭되는 현재 블록이 있으면 그 시각으로 reschedule.
        for (ReplanOption.StoredBlock sb : option.getProposedBlocks()) {
            Deque<PlanBlock> queue = blocksByTask.get(sb.taskId());
            if (queue == null || queue.isEmpty()) {
                continue; // 제안에 있으나 현재 블록이 없는 태스크(미배치 등) → 건너뜀
            }
            PlanBlock block = queue.poll();
            planBlockRepository.reschedule(block.getId(), sb.startAt(), sb.endAt(), planId);
        }

        // 선택 표시 — 같은 계획의 다른 대안은 해제하고 이 대안만 선택.
        for (ReplanOption sibling : replanOptionRepository.findByWeeklyPlanIdOrderByCreatedAtAsc(planId)) {
            if (sibling.getId().equals(optionId)) {
                sibling.markSelected(clock.now());
            } else if (sibling.isSelected()) {
                sibling.unselect();
            }
        }

        planBlockRepository.flush(); // 이동·상태 반영 후 재계산이 새 상태를 봄
        recalculator.recalculate(List.of(planId));

        WeeklyPlan latest = weeklyPlanRepository.findById(planId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        // 방어적 재조회. recalculator는 JdbcTemplate로 total을 UPDATE하는데(JPA 바깥) plan은 진입부에서
        // 이미 컨텍스트에 올라가 있어, 위 재조회가 1차 캐시의 낡은 인스턴스를 줄 수 있다.
        // 지금은 위 루프의 reschedule(@Modifying(clearAutomatically=true))이 컨텍스트를 비워 우연히 맞는다 —
        // 즉 "재배치가 한 건이라도 일어난다"는 조건에 정합성이 매달려 있다. 제안이 현재 블록과 하나도
        // 매칭되지 않으면 clear가 일어나지 않는데, 그 경우엔 블록도 안 바뀌어 total이 그대로라 지금은 드러나지 않는다.
        // 그 우연이 깨지면(reschedule의 clear 제거, 블록을 바꾸는 다른 경로 추가) 바로 결함이 되므로 명시적으로 읽는다.
        // 같은 함정이 PlanBlockService.applyBatch에서는 실제로 터졌다(응답 total=0 실측).
        entityManager.refresh(latest);
        List<PlanBlockResponse> blocks = planBlockRepository.findViewsByWeeklyPlanId(planId)
                .stream().map(PlanBlockResponse::fromView).toList();
        return WeeklyPlanView.of(WeeklyPlanResponse.from(latest, blocks.size()), blocks);
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

    /** 저장된 엔티티 → 응답 (재조회 GET). JSONB proposed_blocks(StoredBlock)를 응답 블록으로 복원. */
    private ReplanOptionResponse toResponse(ReplanOption entity) {
        List<ReplanOptionResponse.ProposedBlock> proposed = entity.getProposedBlocks().stream()
                .map(b -> new ReplanOptionResponse.ProposedBlock("TASK", b.taskId(), b.startAt(), b.endAt()))
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
