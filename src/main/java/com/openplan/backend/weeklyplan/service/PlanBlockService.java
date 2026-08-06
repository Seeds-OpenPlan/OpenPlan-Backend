package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.service.port.WeeklyPlanTotalsRecalculator;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.domain.TaskStatus;
import com.openplan.backend.task.repository.OwnedTask;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.PlanBlockType;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.dto.PlanBlockCreateRequest;
import com.openplan.backend.weeklyplan.dto.PlanBlockResponse;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 블록 배치 유스케이스 파사드 (ST-B2-08 / PLAN-06·07). 이번 슬라이스는 TASK 블록만 — SCHEDULE·해제·이동은 후속.
 *
 * <p><b>부작용(같은 tx)</b>: ① 미배치 태스크 배치 → {@code task.onBlockAssigned()}(UNASSIGNED→IN_PROGRESS, TT-1)
 * ② 확정 계획 편집 재개 → DRAFT 복귀 ③ 주간계획 total 재계산({@link WeeklyPlanTotalsRecalculator}).
 * <b>순서(C1)</b>: 블록 save + 엔티티 변경 → <b>flush</b> → recalculate(JDBC). flush 후 엔티티가 clean이라
 * 커밋 시 stale total(0)로 덮어쓰지 않는다.
 *
 * <p>겹침·가용시간 초과 등은 <b>쓰기에서 막지 않는다</b> — 검증 엔진(ST-B2-09)이 판정한다(배치는 관대, 검증이 분리).
 */
@Service
public class PlanBlockService {

    private final PlanBlockRepository planBlockRepository;
    private final WeeklyPlanRepository weeklyPlanRepository;
    private final TaskRepository taskRepository;
    private final WeeklyPlanTotalsRecalculator recalculator;
    private final ErrorMessages errorMessages;
    private final UserClock clock;

    public PlanBlockService(PlanBlockRepository planBlockRepository, WeeklyPlanRepository weeklyPlanRepository,
                            TaskRepository taskRepository, WeeklyPlanTotalsRecalculator recalculator,
                            ErrorMessages errorMessages, UserClock clock) {
        this.planBlockRepository = planBlockRepository;
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.taskRepository = taskRepository;
        this.recalculator = recalculator;
        this.errorMessages = errorMessages;
        this.clock = clock;
    }

    /**
     * TASK 블록 배치 (PLAN-06·07). 계획 소유(404) → 구조/시각 검증(422) → 태스크 소유(404)·완료 불가(422) →
     * 확정이면 DRAFT 복귀 → 블록 저장 + 태스크 상태 미러 → flush → total 재계산.
     */
    @Transactional
    public PlanBlockResponse createBlock(UUID userId, UUID planId, PlanBlockCreateRequest req) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (계획 부재·타인)

        if (!PlanBlockType.TASK.name().equals(req.blockType())) { // 이번 슬라이스는 TASK만 (SCHEDULE 후속)
            throw invalidField("blockType", "unsupported");
        }
        if (req.taskId() == null) {
            throw invalidField("taskId", "required");
        }
        if (!req.startAt().isBefore(req.endAt())) { // 422 (ck_plan_block_range 사전 검증)
            throw new OpenPlanException(ErrorCode.E_PLAN_002);
        }

        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(req.taskId(), userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (태스크 부재·타인)
        Task task = owned.task();
        if (task.getStatus() == TaskStatus.COMPLETED) { // 결정 B — 완료 태스크 배치 금지
            throw invalidField("taskId", "completed");
        }

        plan.reopenToDraftIfConfirmed(); // 결정 C — 확정 편집 재개 → DRAFT

        PlanBlock block = PlanBlock.forTask(planId, req.taskId(), req.startAt(), req.endAt(), clock.now());
        planBlockRepository.save(block);
        task.onBlockAssigned();          // TT-1 미배치→진행중 (COMPLETED는 위에서 이미 차단)

        planBlockRepository.flush();      // C1 — 블록·태스크·계획 변경 DB 반영 후 재계산이 새 상태를 봄
        recalculator.recalculate(List.of(planId)); // 주 total 재계산(잔여 블록 절대값)

        return PlanBlockResponse.from(block);
    }

    /** field·rule로 카탈로그 키({@code validation.{field}.{rule}})를 유도해 422 E-COM-009 필드 오류를 만든다. */
    private OpenPlanException invalidField(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
