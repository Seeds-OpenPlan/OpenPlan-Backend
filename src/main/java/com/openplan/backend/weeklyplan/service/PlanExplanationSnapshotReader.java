package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.rule.model.BlockType;
import com.openplan.backend.rule.model.BlockView;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.ValidationIssueRecord;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.ValidationIssueRecordRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link PlanExplanationService}의 DB 읽기 전담(스냅샷+이슈 조립). {@link AutoPlacementSnapshotReader}와
 * 같은 이유로 별도 빈이다 — 읽기 tx는 <b>여기서 끝나고</b>, AI 호출(최대 {@code AiProperties.timeout},
 * 기본 20초의 동기 HTTP)은 이 메서드가 반환한 뒤 tx 밖에서 호출자가 부른다. JDBC 커넥션을 쥔 채
 * 네트워크를 기다리면 동시 요청 수만큼 HikariCP 풀이 묶여 무관한 요청까지 실패할 수 있다(PR #39 지적과
 * 동일 — {@link AutoPlacementSnapshotReader} 클래스 주석 참고).
 *
 * <p><b>메서드는 반드시 public 이어야 한다</b> — Spring 프록시 기반 {@code @Transactional}은 public
 * 메서드 호출만 가로챈다({@link AutoPlacementSnapshotReader}와 같은 주의).
 */
@Component
public class PlanExplanationSnapshotReader {

    private final WeeklyPlanRepository weeklyPlanRepository;
    private final PlanBlockRepository planBlockRepository;
    private final ValidationIssueRecordRepository validationIssueRepository;
    private final PlanSnapshotAssembler assembler;

    public PlanExplanationSnapshotReader(WeeklyPlanRepository weeklyPlanRepository,
                                         PlanBlockRepository planBlockRepository,
                                         ValidationIssueRecordRepository validationIssueRepository,
                                         PlanSnapshotAssembler assembler) {
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.planBlockRepository = planBlockRepository;
        this.validationIssueRepository = validationIssueRepository;
        this.assembler = assembler;
    }

    /**
     * 스냅샷(현재 블록 전량) + 영속된 검증 이슈의 {@code reason} 문자열 목록을 조립한다.
     * 검증을 한 번도 안 돌렸으면(= 영속 이슈 없음) 빈 목록을 보낸다 — <b>문제를 지어내지 않는다</b>
     * (AI 계약 쪽도 issues 가 비면 "문제 없음"을 단정하지 않고 배치만 설명하도록 경계가 맞춰져 있다).
     * 계획 부재·타인 → 404.
     */
    @Transactional(readOnly = true)
    public ExplanationInput read(UUID userId, UUID planId) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        List<BlockView> blocks = planBlockRepository.findByWeeklyPlanId(planId).stream()
                .map(this::toBlockView).toList();

        List<String> issues = validationIssueRepository.findByWeeklyPlanId(planId).stream()
                .map(ValidationIssueRecord::getMessage).toList();

        PlanSnapshot snapshot = assembler.assemble(userId, plan.getWeekStartDate(), blocks, Map.of());
        return new ExplanationInput(snapshot, issues);
    }

    private BlockView toBlockView(PlanBlock b) {
        return new BlockView(b.getId(), BlockType.valueOf(b.getBlockType().name()),
                b.getTaskId(), b.getScheduleId(), b.getStartAt(), b.getEndAt());
    }

    /** 설명 호출 재료 한 벌 — snapshot과 그 계획에 영속된 검증 이슈의 reason 문자열. */
    public record ExplanationInput(PlanSnapshot snapshot, List<String> issues) {
    }
}
