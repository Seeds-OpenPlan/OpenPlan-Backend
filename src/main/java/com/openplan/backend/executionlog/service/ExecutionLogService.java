package com.openplan.backend.executionlog.service;

import com.openplan.backend.executionlog.domain.ExecutionLog;
import com.openplan.backend.executionlog.domain.ExecutionResult;
import com.openplan.backend.executionlog.dto.ExecutionLogCreateRequest;
import com.openplan.backend.executionlog.dto.ExecutionLogResponse;
import com.openplan.backend.executionlog.repository.ExecutionLogRepository;
import com.openplan.backend.executionlog.service.port.PlanBlockOwnershipChecker;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.task.repository.OwnedTask;
import com.openplan.backend.task.repository.TaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 수행 이력 유스케이스 (PLAN-15 · DASH-05). 검증은 {@link ExecutionLogValidator}, 시각은 {@link UserClock},
 * 태스크 소유 판정은 task/ 공개 계약({@link TaskRepository#findOwnedWithProjectStatus})을 재사용한다
 * (재구현 금지 — ADR-B2-03-004). {@code plan_blocks} 접촉은 {@link PlanBlockOwnershipChecker} port 경유.
 *
 * <p>검사 순서: <b>404(태스크 부재·타인) → 422(값·블록 참조)</b>. 낙관락은 없다 — 기록은 추가만 하고
 * 편집하지 않으므로 경합할 대상이 없다(명세에 편집·삭제 오퍼레이션 부재).
 */
@Service
public class ExecutionLogService {

    private final ExecutionLogRepository repository;
    private final ExecutionLogValidator validator;
    private final TaskRepository taskRepository;
    private final PlanBlockOwnershipChecker planBlockOwnershipChecker;
    private final UserClock clock;

    public ExecutionLogService(ExecutionLogRepository repository, ExecutionLogValidator validator,
                               TaskRepository taskRepository,
                               PlanBlockOwnershipChecker planBlockOwnershipChecker, UserClock clock) {
        this.repository = repository;
        this.validator = validator;
        this.taskRepository = taskRepository;
        this.planBlockOwnershipChecker = planBlockOwnershipChecker;
        this.clock = clock;
    }

    /**
     * 수행 이력 기록 (PLAN-15). 태스크 소유 확인 → 값 검증 → 계획 블록 참조 확인 → 영속.
     *
     * <p>종료된(CLOSED) 프로젝트 소속 태스크도 기록을 <b>허용한다</b> — D-10 가드는 계획을 바꾸는 쓰기
     * (편집·상태·삭제)에 걸리는 규칙이고, 이력은 "이미 일어난 일"의 기록이라 성격이 다르다.
     * 명세도 이 오퍼레이션에 E-PROJ-005를 문서화하지 않았다.
     */
    @Transactional
    public ExecutionLogResponse record(UUID userId, UUID taskId, ExecutionLogCreateRequest req) {
        // 소유 확인과 예상 시간 스냅샷을 같은 조회로 해결한다 — 추가 쿼리 불요.
        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(taskId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 — 존재 은닉

        validator.validateTimes(req.startedAt(), req.endedAt());          // 422
        validator.validateActualMinutes(req.actualMinutes());             // 422
        ExecutionResult result = validator.resolveResult(req.result());   // 422

        if (req.planBlockId() != null
                && !planBlockOwnershipChecker.isOwnedBy(req.planBlockId(), userId)) {
            validator.rejectUnownedPlanBlock();                           // 422 — 부재·타인 구분 안 함
        }

        // ST-B2-14 AC② — "당시" 예상 시간을 여기서 박제한다. 이후 태스크를 편집해도 이 값은 안 변한다.
        ExecutionLog log = ExecutionLog.record(
                userId, taskId, req.planBlockId(),
                req.startedAt(), req.endedAt(), req.actualMinutes(),
                owned.task().getEstimatedMinutes(),
                result, req.memo(), clock.now());
        repository.save(log);
        return ExecutionLogResponse.from(log);
    }
}
