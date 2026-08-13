package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.Severity;
import com.openplan.backend.rule.model.ValidationIssue;
import com.openplan.backend.rule.model.ValidationReport;
import com.openplan.backend.rule.port.PlanValidationPort;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.ValidationIssueRecord;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.PlanBlockCreateRequest;
import com.openplan.backend.weeklyplan.dto.ValidateWeeklyPlanRequest;
import com.openplan.backend.weeklyplan.dto.ValidateWeeklyPlanRequest.VirtualTaskEdit;
import com.openplan.backend.weeklyplan.dto.ValidationIssueResponse;
import com.openplan.backend.weeklyplan.dto.ValidationReportResponse;
import com.openplan.backend.weeklyplan.dto.WeeklyPlanResponse;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.ValidationIssueRecordRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 검증 실행·확정 (ST-B2-09 / SS-06·PLAN-03·28). <b>규칙은 만들지 않는다</b> — 스냅샷을 조립해
 * {@link PlanValidationPort}(엔진)에 넘기고 결과를 영속/확정한다(엔진 직접 참조 금지, 포트 경유).
 */
@Service
public class WeeklyPlanValidationService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyPlanValidationService.class);

    private final WeeklyPlanRepository weeklyPlanRepository;
    private final PlanBlockRepository planBlockRepository;
    private final ValidationIssueRecordRepository validationIssueRepository;
    private final PlanSnapshotAssembler assembler;
    private final PlanValidationPort validationPort;
    private final UserClock clock;

    public WeeklyPlanValidationService(WeeklyPlanRepository weeklyPlanRepository,
                                       PlanBlockRepository planBlockRepository,
                                       ValidationIssueRecordRepository validationIssueRepository,
                                       PlanSnapshotAssembler assembler,
                                       PlanValidationPort validationPort,
                                       UserClock clock) {
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.planBlockRepository = planBlockRepository;
        this.validationIssueRepository = validationIssueRepository;
        this.assembler = assembler;
        this.validationPort = validationPort;
        this.clock = clock;
    }

    /**
     * 검증 실행 (SS-06). {@code virtualBlocks} 키 있으면 dry-run(무영속), 없으면 저장 초안 판정 + validation_issues 영속.
     * <b>위반은 오류가 아니다</b> — 200 + {@link ValidationReportResponse}. 404는 계획 소유 확인으로 낸다.
     */
    @Transactional
    public ValidationReportResponse validate(UUID userId, UUID planId, ValidateWeeklyPlanRequest request) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        boolean dryRun = request != null && request.virtualBlocks() != null;

        List<BlockView> blocks;
        Map<UUID, VirtualTaskEdit> edits;
        if (dryRun) {
            blocks = toVirtualBlockViews(request.virtualBlocks());
            edits = toEditMap(request.virtualTaskEdits());
        } else {
            blocks = planBlockRepository.findByWeeklyPlanId(planId).stream().map(this::toBlockView).toList();
            edits = Map.of();
        }

        PlanSnapshot snapshot = assembler.assemble(userId, plan.getWeekStartDate(), blocks, edits);
        ValidationReport report = validationPort.validate(snapshot);

        List<ValidationIssueResponse> issues = dryRun
                ? report.issues().stream().map(i -> ValidationIssueResponse.of(i, null)).toList()
                : persistIssues(planId, report);

        return new ValidationReportResponse(dryRun, report.savable(), issues, report.evaluatedAt());
    }

    /**
     * 확정 (PLAN-03·28). 엔진 재검증 → 차단(BLOCK) ≥1이면 409 E-PLAN-004(details.issues=차단만),
     * 0이면 validation_issues 영속 + status=CONFIRMED(단일 tx). 경고(WARNING)는 남아도 확정된다.
     * Idempotency-Key는 중복 클릭 방어용 힌트 — 재확정은 자연 멱등(이미 CONFIRMED + 동일 판정)이라 별도 저장소는 두지 않는다.
     */
    @Transactional
    public WeeklyPlanResponse confirm(UUID userId, UUID planId, String idempotencyKey) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        List<BlockView> blocks = planBlockRepository.findByWeeklyPlanId(planId).stream()
                .map(this::toBlockView).toList();
        PlanSnapshot snapshot = assembler.assemble(userId, plan.getWeekStartDate(), blocks, Map.of());
        ValidationReport report = validationPort.validate(snapshot);

        List<ValidationIssue> blocking = report.issues().stream()
                .filter(i -> i.severity() == Severity.BLOCK).toList();
        if (!blocking.isEmpty()) { // 409 — 차단만 동봉
            List<ValidationIssueResponse> issues = blocking.stream()
                    .map(i -> ValidationIssueResponse.of(i, null)).toList();
            throw new OpenPlanException(ErrorCode.E_PLAN_004, Map.of("issues", issues));
        }

        persistIssues(planId, report); // 경고 잔존 영속
        plan.confirm(clock.now());     // status=CONFIRMED
        log.info("weekly plan confirmed: planId={}, userId={}, idempotencyKey={}, warnings={}",
                planId, userId, idempotencyKey, report.issues().size());
        return WeeklyPlanResponse.from(plan, blocks.size());
    }

    /** 재검증 = 전면 교체: 기존 이슈 삭제 후 새로 삽입. 응답에 부여한 id를 그대로 싣는다. */
    private List<ValidationIssueResponse> persistIssues(UUID planId, ValidationReport report) {
        validationIssueRepository.deleteByWeeklyPlanId(planId);
        List<ValidationIssueResponse> out = new ArrayList<>();
        for (ValidationIssue i : report.issues()) {
            ValidationIssueRecord record = ValidationIssueRecord.from(planId, i);
            validationIssueRepository.save(record);
            out.add(ValidationIssueResponse.of(i, record.getId()));
        }
        return out;
    }

    private BlockView toBlockView(PlanBlock b) {
        return new BlockView(b.getId(), BlockType.valueOf(b.getBlockType().name()),
                b.getTaskId(), b.getScheduleId(), b.getStartAt(), b.getEndAt());
    }

    /** 가상 블록(dry-run) → BlockView. 합성 id 부여(영속 안 함). blockType·시각 누락은 422. */
    private List<BlockView> toVirtualBlockViews(List<PlanBlockCreateRequest> virtualBlocks) {
        List<BlockView> views = new ArrayList<>();
        for (PlanBlockCreateRequest b : virtualBlocks) {
            if (b.blockType() == null || b.startAt() == null || b.endAt() == null) {
                throw new OpenPlanException(ErrorCode.E_COM_009,
                        Map.of("field", "virtualBlocks", "rule", "required"));
            }
            BlockType type;
            try {
                type = BlockType.valueOf(b.blockType());
            } catch (IllegalArgumentException e) {
                throw new OpenPlanException(ErrorCode.E_COM_009,
                        Map.of("field", "virtualBlocks.blockType", "rule", "invalid"));
            }
            views.add(new BlockView(UUID.randomUUID(), type, b.taskId(), null, b.startAt(), b.endAt()));
        }
        return views;
    }

    private Map<UUID, VirtualTaskEdit> toEditMap(List<VirtualTaskEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return Map.of();
        }
        return edits.stream()
                .filter(e -> e.taskId() != null)
                .collect(Collectors.toMap(VirtualTaskEdit::taskId, Function.identity(), (a, b) -> b));
    }
}
