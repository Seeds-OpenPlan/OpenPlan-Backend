package com.openplan.backend.task.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.ProjectAutoCloseEvaluator;
import com.openplan.backend.project.service.port.WeeklyPlanTotalsRecalculator;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.domain.TaskStatus;
import com.openplan.backend.task.domain.WbsItem;
import com.openplan.backend.task.dto.TaskCreateRequest;
import com.openplan.backend.task.dto.TaskListQuery;
import com.openplan.backend.task.dto.TaskResponse;
import com.openplan.backend.task.dto.TaskStatusToggleRequest;
import com.openplan.backend.task.dto.TaskUpdateRequest;
import com.openplan.backend.task.dto.UnassignedTaskQuery;
import com.openplan.backend.task.dto.UnassignedTaskResponse;
import com.openplan.backend.task.dto.WbsItemResponse;
import com.openplan.backend.task.dto.WbsRangeRequest;
import com.openplan.backend.task.repository.OwnedTask;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.repository.WbsItemRepository;
import com.openplan.backend.task.service.port.PlanBlockStatusMirror;
import com.openplan.backend.task.service.port.TaskCategoryChecker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 태스크 유스케이스 파사드 — 검사 순서·tx 경계를 소유한다. 검증은 {@link TaskValidator}, 시각은
 * {@link UserClock}, 프로젝트 소유·상태는 project/ 공개 계약({@link ProjectRepository}·
 * {@link ProjectAutoCloseEvaluator})을 재사용한다(재구현 금지 — ADR-B2-03-004).
 *
 * <p>본 슬라이스는 생성(EP-2)만 구현한다. 조회/편집/토글/삭제는 후속 슬라이스에서 추가한다.
 */
@Transactional(readOnly = true)
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskValidator validator;
    private final ProjectRepository projectRepository;
    private final ProjectAutoCloseEvaluator autoCloseEvaluator;
    private final TaskCategoryChecker categoryChecker;
    private final PlanBlockStatusMirror planBlockStatusMirror;
    private final WeeklyPlanTotalsRecalculator weeklyPlanTotalsRecalculator;
    private final WbsItemRepository wbsItemRepository;
    private final UserClock clock;

    public TaskService(TaskRepository taskRepository, TaskValidator validator,
                       ProjectRepository projectRepository, ProjectAutoCloseEvaluator autoCloseEvaluator,
                       TaskCategoryChecker categoryChecker, PlanBlockStatusMirror planBlockStatusMirror,
                       WeeklyPlanTotalsRecalculator weeklyPlanTotalsRecalculator,
                       WbsItemRepository wbsItemRepository, UserClock clock) {
        this.taskRepository = taskRepository;
        this.validator = validator;
        this.projectRepository = projectRepository;
        this.autoCloseEvaluator = autoCloseEvaluator;
        this.categoryChecker = categoryChecker;
        this.planBlockStatusMirror = planBlockStatusMirror;
        this.weeklyPlanTotalsRecalculator = weeklyPlanTotalsRecalculator;
        this.wbsItemRepository = wbsItemRepository;
        this.clock = clock;
    }

    @Transactional
    public TaskResponse create(UUID userId, UUID projectId, TaskCreateRequest req) {
        autoCloseEvaluator.closeOverdue(userId);

        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (AC-C-7)

        if (project.getStatus() == ProjectStatus.CLOSED) { // D-10 가드 → 422 (PAUSED 통과, AC-C-7)
            throw new OpenPlanException(ErrorCode.E_PROJ_005,
                    Map.of("fields", List.of(Map.of("field", "project.status"))));
        }

        String title = validator.validateTitle(req.title());          // 422 (AC-C-3)
        validator.validateEstimatedMinutes(req.estimatedMinutes());    // 422 (AC-C-4)
        validator.validatePriority(req.priority());                    // 422 — 1·2·3만 (제품 3단계)

        if (req.categoryId() != null && !categoryChecker.existsOwned(req.categoryId(), userId)) {
            throw new OpenPlanException(ErrorCode.E_COM_004);           // 404 (AC-C-5, D-8)
        }

        Task task = new Task(projectId, req.categoryId(), title, req.memo(),
                req.estimatedMinutes(), req.priority(), req.dueDate(), clock.now());
        taskRepository.save(task);
        return TaskResponse.from(task);
    }

    public Page<TaskResponse> list(UUID userId, UUID projectId, TaskListQuery query) {
        projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (AC-L-4)

        TaskStatus statusFilter = parseStatusFilter(query.getStatus());          // 미정의값 → 422 (AC-L-2)
        Pageable pageable = PageRequest.of(query.getPage() - 1, query.getSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));   // D-9 서버 고정

        Page<Task> page = (statusFilter == null)
                ? taskRepository.findByProjectId(projectId, pageable)
                : taskRepository.findByProjectIdAndStatus(projectId, statusFilter, pageable);
        return page.map(TaskResponse::from);
    }

    public Page<UnassignedTaskResponse> listUnassigned(UUID userId, UnassignedTaskQuery query) {
        String status = query.getStatus();
        if (status != null && !status.isBlank() && !"UNASSIGNED".equals(status.trim())) { // 422 — 타 값만 거부(AC-U-3)
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("fields", List.of(Map.of(
                    "field", "status", "rule", "allowed", "message", "status는 UNASSIGNED만 허용됩니다."))));
        }
        // 생략·빈 값 = UNASSIGNED 기본값(쿼리 자체가 UNASSIGNED 필터라 별도 세팅 불요)

        autoCloseEvaluator.closeOverdue(userId); // TB-5 — 평가 경유 조회 의무 (stale IN_PROGRESS 우회 차단)

        Pageable pageable = PageRequest.of(query.getPage() - 1, query.getSize()); // 정렬은 쿼리에 고정(D-9)
        return taskRepository.findUnassignedWithProjectName(userId, pageable)
                .map(UnassignedTaskResponse::from);
    }

    /** status 필터 문자열 → enum. null/빈 값 = 전체(null 반환). 미정의 열거값 → 422 E-COM-009(AC-L-2). */
    private TaskStatus parseStatusFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return TaskStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("fields", List.of(Map.of(
                    "field", "status", "rule", "enum", "message", "허용되지 않는 status 값입니다."))));
        }
    }

    public TaskResponse detail(UUID userId, UUID taskId) {
        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(taskId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (AC-R-2)
        return TaskResponse.from(owned.task());
    }

    @Transactional
    public TaskResponse update(UUID userId, UUID taskId, TaskUpdateRequest req) {
        autoCloseEvaluator.closeOverdue(userId);

        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(taskId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (AC-E-3 taskId)
        Task task = owned.task();

        if (owned.projectStatus() == ProjectStatus.CLOSED) { // 422 — version보다 먼저 (D-10, AC-E-5)
            throw new OpenPlanException(ErrorCode.E_PROJ_005,
                    Map.of("fields", List.of(Map.of("field", "project.status"))));
        }
        if (req.getVersion() != task.getVersion()) { // 409 — 최신 TaskResponse 동봉 (AC-E-4)
            throw new OpenPlanException(ErrorCode.E_COM_006,
                    Map.of("latest", TaskResponse.from(task)));
        }

        // 부분 병합 — 담겨 온 필드만 검증·반영, 안 담긴 필드는 기존 값 유지(true PATCH).
        String title = task.getTitle();
        if (req.isProvided("title")) {
            title = validator.validateTitle(req.getTitle());          // 422 (AC-E-3, 생성과 동일 코드)
        }
        Integer estimatedMinutes = task.getEstimatedMinutes();
        if (req.isProvided("estimatedMinutes")) {
            validator.validateEstimatedMinutes(req.getEstimatedMinutes()); // 422
            estimatedMinutes = req.getEstimatedMinutes();
        }
        Integer priority = task.getPriority();
        if (req.isProvided("priority")) {
            validator.validatePriority(req.getPriority());            // 422 — 생성과 동일 (AC-E-3)
            priority = req.getPriority();
        }
        String memo = req.isProvided("memo") ? req.getMemo() : task.getMemo();
        LocalDate dueDate = req.isProvided("dueDate") ? req.getDueDate() : task.getDueDate();

        UUID categoryId = task.getCategoryId();
        if (req.isProvided("categoryId")) {
            if (req.getCategoryId() != null && !categoryChecker.existsOwned(req.getCategoryId(), userId)) {
                throw new OpenPlanException(ErrorCode.E_COM_004);      // 404 (AC-E-3 categoryId, D-8)
            }
            categoryId = req.getCategoryId();                         // 값 또는 null(해제)
        }

        task.edit(title, memo, estimatedMinutes, priority, dueDate, categoryId);
        taskRepository.flush(); // @Version 증가를 응답에 반영. 잔여 경합 → OptimisticLockException → 409
        return TaskResponse.from(task);
    }

    @Transactional
    public TaskResponse toggleCompletion(UUID userId, UUID taskId, TaskStatusToggleRequest req) {
        autoCloseEvaluator.closeOverdue(userId);

        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(taskId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (AC-S-6)
        Task task = owned.task();

        if (owned.projectStatus() == ProjectStatus.CLOSED) { // ① 422 — no-op보다 먼저 (D-10, AC-S-6)
            throw new OpenPlanException(ErrorCode.E_PROJ_005,
                    Map.of("fields", List.of(Map.of("field", "project.status"))));
        }

        boolean currentlyCompleted = task.getStatus() == TaskStatus.COMPLETED;
        if (req.completed() == currentlyCompleted) { // ② no-op 단락 — version 검사보다 먼저 (AC-S-5, 더블클릭 내성)
            return TaskResponse.from(task);          // version 미증가·미러 미발동
        }
        if (req.version() != task.getVersion()) {    // ③ 409 (AC-S-6)
            throw new OpenPlanException(ErrorCode.E_COM_006,
                    Map.of("latest", TaskResponse.from(task)));
        }

        if (req.completed()) {                        // 완료로 표시 (TT-3)
            task.complete();                          // UNASSIGNED면 엔티티가 E_PROJ_003 throw (TT-6, AC-S-4)
            planBlockStatusMirror.mirrorStatus(taskId, TaskStatus.COMPLETED); // 블록 → COMPLETED (AC-S-1)
        } else {                                      // 미완료로 되돌리기
            boolean hasBlocks = planBlockStatusMirror.hasBlocks(taskId);
            TaskStatus landing = task.reopen(hasBlocks);
            if (landing == TaskStatus.IN_PROGRESS) {  // TT-4 (블록≥1)
                planBlockStatusMirror.mirrorStatus(taskId, TaskStatus.IN_PROGRESS); // 블록 → SCHEDULED (AC-S-2)
            }
            // landing == UNASSIGNED (TT-5, 블록0) → 미러 미발동 (AC-S-3, D-1c)
        }

        taskRepository.flush(); // @Version 증가 + 블록 미러 UPDATE 동일 tx 커밋 (TB-1 원자성)
        return TaskResponse.from(task);
    }

    @Transactional
    public void delete(UUID userId, UUID taskId) {
        autoCloseEvaluator.closeOverdue(userId);

        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(taskId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (AC-D-4: 부재·타인·재삭제)

        if (owned.projectStatus() == ProjectStatus.CLOSED) { // 422 (D-10, AC-D-4)
            throw new OpenPlanException(ErrorCode.E_PROJ_005,
                    Map.of("fields", List.of(Map.of("field", "project.status"))));
        }

        List<UUID> affected = weeklyPlanTotalsRecalculator.captureAffectedWeeklyPlanIdsByTask(taskId); // 삭제 전 수집(C1)
        taskRepository.delete(owned.task());
        taskRepository.flush();                                    // C1 — cascade가 DB 반영되어야 재계산이 삭제 후를 봄
        weeklyPlanTotalsRecalculator.recalculate(affected);       // TB-4 잔여 블록 절대값 재계산 (AC-D-2)
    }

    /**
     * WBS 기간 설정/조정 (ST-B2-05 / PUT — 업서트). 검사 순서 404 → 422 CLOSED → 422 E-WBS-001은
     * update/toggleCompletion과 동일 관례(D-10이 값 검증보다 먼저). version 검사는 없다 —
     * wbs_items에 낙관락 컬럼이 없고 정본 요청 바디에도 version이 없다(WbsItem 클래스 상단 참고,
     * 업서트라 last-write-wins가 설계 의도).
     */
    @Transactional
    public WbsItemResponse saveWbsRange(UUID userId, UUID taskId, WbsRangeRequest req) {
        autoCloseEvaluator.closeOverdue(userId);

        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(taskId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (존재 은닉)
        Task task = owned.task();

        if (owned.projectStatus() == ProjectStatus.CLOSED) { // 422 — 값 검증보다 먼저 (D-10)
            throw new OpenPlanException(ErrorCode.E_PROJ_005,
                    Map.of("fields", List.of(Map.of("field", "project.status"))));
        }

        validator.validateWbsRange(req.startDate(), req.endDate()); // 422 E-WBS-001

        // 원자적 업서트(경합 안전) 후 재조회 — 근거는 WbsItemRepository.upsert 참고.
        wbsItemRepository.upsert(task.getProjectId(), taskId, req.startDate(), req.endDate());
        WbsItem saved = wbsItemRepository.findByTaskId(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "upsert 직후 wbs_items 재조회 실패 — task_id=" + taskId)); // 발생 시 버그(같은 tx 내 재조회)

        return WbsItemResponse.of(saved, task.getTitle());
    }
}
