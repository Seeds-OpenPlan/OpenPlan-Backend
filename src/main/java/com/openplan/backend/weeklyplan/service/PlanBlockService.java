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
        requireFiveMinuteAligned(req.startAt(), "startAt"); // 422 — 블록 시각은 5분 단위(정본 E-COM-009)
        requireFiveMinuteAligned(req.endAt(), "endAt");

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
        } else { // SCHEDULE — 일정을 새로 만들며 배치 (PLAN-08). 일정 입력은 중첩 schedule 객체(정본)
            PlanBlockCreateRequest.ScheduleInput s = req.schedule();
            if (s == null) {
                throw invalidField("schedule", "required");
            }
            String title = scheduleValidator.validateTitle(s.title());
            scheduleValidator.validateEstimatedMinutes(s.estimatedMinutes());
            scheduleValidator.validatePriority(s.priority());
            schedule = new Schedule(userId, title, s.estimatedMinutes(), s.priority(),
                    req.startAt(), req.endAt(), s.memo(), clock.now());
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

        // title·projectId는 방금 확보한 참조에서 채운다(조인 재조회 불요). SCHEDULE은 projectId 없음.
        String title = (type == PlanBlockType.TASK) ? task.getTitle() : schedule.getTitle();
        UUID projectId = (type == PlanBlockType.TASK) ? task.getProjectId() : null;
        return PlanBlockResponse.of(block, title, projectId);
    }

    /**
     * 블록 삭제/해제 (PLAN-16·18 / TUT-07). {@code createBlock}의 부작용을 역방향으로 대칭 처리한다:
     * <ul>
     *   <li><b>TASK</b>: 블록 삭제 → 그 태스크에 <b>남은 블록이 없으면</b> {@code onLastBlockRemoved()}
     *       (IN_PROGRESS→UNASSIGNED, TT-2). 다른 블록이 남아 있으면 상태 유지(불변식 "IN_PROGRESS ⇔ 블록≥1").</li>
     *   <li><b>SCHEDULE</b>: 블록 삭제 → 함께 만들었던 {@code schedules} 행도 연쇄 삭제(PLAN-18).</li>
     * </ul>
     * 공통: 확정 계획이면 DRAFT 복귀 → flush → 주 total 재계산(C1). 부재·타인 → 404(존재 은닉). 성공 204.
     */
    @Transactional
    public void deleteBlock(UUID userId, UUID blockId) {
        PlanBlock block = planBlockRepository.findByIdAndUserId(blockId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (블록 부재·타인)

        UUID planId = block.getWeeklyPlanId();
        weeklyPlanRepository.findById(planId).ifPresent(WeeklyPlan::reopenToDraftIfConfirmed); // 확정 편집 재개→DRAFT

        if (block.getBlockType() == PlanBlockType.TASK) {
            UUID taskId = block.getTaskId();
            planBlockRepository.delete(block);
            // 자신을 뺀 뒤 남은 블록이 없으면 미배치 복귀 (TT-2 — COMPLETED·UNASSIGNED는 onLastBlockRemoved가 방어)
            if (!planBlockRepository.existsByTaskIdAndIdNot(taskId, blockId)) {
                taskRepository.findById(taskId).ifPresent(Task::onLastBlockRemoved);
            }
        } else { // SCHEDULE — 블록 + 연결 일정 연쇄 삭제 (PLAN-18)
            UUID scheduleId = block.getScheduleId();
            planBlockRepository.delete(block);
            if (scheduleId != null) {
                scheduleRepository.deleteById(scheduleId);
            }
        }

        planBlockRepository.flush(); // C1 — 삭제·상태 변경 DB 반영 후 재계산이 새 상태를 봄
        recalculator.recalculate(List.of(planId));
    }

    /** blockType 문자열 → enum. 미정의값 → 422 E-COM-009. */
    private PlanBlockType parseBlockType(String raw) {
        try {
            return PlanBlockType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw invalidField("blockType", "invalid");
        }
    }

    /** 블록 시각 5분 단위 검증(정본 E-COM-009). 5분=300초 경계 + 초 미만 0. UTC 정렬 ⇔ 로컬 정렬(실 오프셋은 15분 배수). */
    private void requireFiveMinuteAligned(java.time.Instant t, String field) {
        if (t.getNano() != 0 || t.getEpochSecond() % 300 != 0) {
            throw invalidField(field, "step");
        }
    }

    /** field·rule로 카탈로그 키({@code validation.{field}.{rule}})를 유도해 422 E-COM-009 필드 오류를 만든다. */
    private OpenPlanException invalidField(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
