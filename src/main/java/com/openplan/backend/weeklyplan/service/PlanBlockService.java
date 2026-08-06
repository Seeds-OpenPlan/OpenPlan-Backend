package com.openplan.backend.weeklyplan.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.service.port.WeeklyPlanTotalsRecalculator;
import com.openplan.backend.schedule.domain.Schedule;
import com.openplan.backend.schedule.repository.ScheduleRepository;
import com.openplan.backend.schedule.service.ScheduleValidator;
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
 * 블록 배치 유스케이스 파사드 (ST-B2-08 / PLAN-06·07·08). TASK(기존 태스크 배치)·SCHEDULE(일정 생성+배치) 지원.
 *
 * <p><b>공통 부작용(같은 tx)</b>: 확정 계획 편집 재개 → DRAFT 복귀 · 주간계획 total 재계산.
 * <b>TASK 추가 부작용</b>: 미배치 태스크 → {@code onBlockAssigned()}(UNASSIGNED→IN_PROGRESS, TT-1).
 * <b>SCHEDULE</b>: schedules 행을 함께 생성(PLAN-08). <b>순서(C1)</b>: 저장/변경 → flush → recalculate(JDBC).
 *
 * <p>겹침·가용 초과 등은 쓰기에서 막지 않는다 — 검증 엔진(ST-B2-09)이 판정(배치는 관대, 검증 분리).
 */
@Service
public class PlanBlockService {

    private final PlanBlockRepository planBlockRepository;
    private final WeeklyPlanRepository weeklyPlanRepository;
    private final TaskRepository taskRepository;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleValidator scheduleValidator;
    private final WeeklyPlanTotalsRecalculator recalculator;
    private final ErrorMessages errorMessages;
    private final UserClock clock;

    public PlanBlockService(PlanBlockRepository planBlockRepository, WeeklyPlanRepository weeklyPlanRepository,
                            TaskRepository taskRepository, ScheduleRepository scheduleRepository,
                            ScheduleValidator scheduleValidator, WeeklyPlanTotalsRecalculator recalculator,
                            ErrorMessages errorMessages, UserClock clock) {
        this.planBlockRepository = planBlockRepository;
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.taskRepository = taskRepository;
        this.scheduleRepository = scheduleRepository;
        this.scheduleValidator = scheduleValidator;
        this.recalculator = recalculator;
        this.errorMessages = errorMessages;
        this.clock = clock;
    }

    /**
     * 블록 배치 (PLAN-06·07·08). 계획 소유(404) → 공통 검증(시각) → blockType 분기(TASK/SCHEDULE) →
     * 확정이면 DRAFT 복귀 → 블록(+SCHEDULE이면 일정) 저장 → flush → total 재계산.
     */
    @Transactional
    public PlanBlockResponse createBlock(UUID userId, UUID planId, PlanBlockCreateRequest req) {
        WeeklyPlan plan = weeklyPlanRepository.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (계획 부재·타인)

        PlanBlockType type = parseBlockType(req.blockType());
        if (!req.startAt().isBefore(req.endAt())) { // 422 (ck_plan_block_range 사전 검증)
            throw new OpenPlanException(ErrorCode.E_PLAN_002);
        }

        // 참조 확보·검증 (blockType별) — plan 변경 전에 끝낸다
        Task task = null;
        Schedule schedule = null;
        if (type == PlanBlockType.TASK) {
            if (req.taskId() == null) {
                throw invalidField("taskId", "required");
            }
            OwnedTask owned = taskRepository.findOwnedWithProjectStatus(req.taskId(), userId)
                    .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (태스크 부재·타인)
            task = owned.task();
            if (task.getStatus() == TaskStatus.COMPLETED) { // 결정 B — 완료 태스크 배치 금지
                throw invalidField("taskId", "completed");
            }
        } else { // SCHEDULE — 일정을 새로 만들며 배치 (PLAN-08)
            String title = scheduleValidator.validateTitle(req.title());
            scheduleValidator.validateEstimatedMinutes(req.estimatedMinutes());
            scheduleValidator.validatePriority(req.priority());
            schedule = new Schedule(userId, title, req.estimatedMinutes(), req.priority(),
                    req.startAt(), req.endAt(), req.memo(), clock.now());
            scheduleRepository.save(schedule);
        }

        plan.reopenToDraftIfConfirmed(); // 결정 C — 확정 편집 재개 → DRAFT

        PlanBlock block = (type == PlanBlockType.TASK)
                ? PlanBlock.forTask(planId, req.taskId(), req.startAt(), req.endAt(), clock.now())
                : PlanBlock.forSchedule(planId, schedule.getId(), req.startAt(), req.endAt(), clock.now());
        planBlockRepository.save(block);

        if (type == PlanBlockType.TASK) {
            task.onBlockAssigned(); // TT-1 미배치→진행중 (COMPLETED는 위에서 차단)
        }

        planBlockRepository.flush(); // C1 — 블록·태스크·일정·계획 변경 DB 반영 후 재계산이 새 상태를 봄
        recalculator.recalculate(List.of(planId)); // 주 total 재계산(잔여 블록 절대값)

        return PlanBlockResponse.from(block);
    }

    /** blockType 문자열 → enum. 미정의값 → 422 E-COM-009. */
    private PlanBlockType parseBlockType(String raw) {
        try {
            return PlanBlockType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw invalidField("blockType", "invalid");
        }
    }

    /** field·rule로 카탈로그 키({@code validation.{field}.{rule}})를 유도해 422 E-COM-009 필드 오류를 만든다. */
    private OpenPlanException invalidField(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
