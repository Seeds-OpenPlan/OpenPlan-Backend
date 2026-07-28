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
import com.openplan.backend.task.dto.TaskCreateRequest;
import com.openplan.backend.task.dto.TaskListQuery;
import com.openplan.backend.task.dto.TaskResponse;
import com.openplan.backend.task.dto.TaskStatusToggleRequest;
import com.openplan.backend.task.dto.TaskUpdateRequest;
import com.openplan.backend.task.dto.UnassignedTaskQuery;
import com.openplan.backend.task.dto.UnassignedTaskResponse;
import com.openplan.backend.task.repository.OwnedTask;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.service.port.PlanBlockStatusMirror;
import com.openplan.backend.task.service.port.TaskCategoryChecker;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskValidator validator;
    private final ProjectRepository projectRepository;
    private final ProjectAutoCloseEvaluator autoCloseEvaluator;
    private final TaskCategoryChecker categoryChecker;
    private final PlanBlockStatusMirror planBlockStatusMirror;
    private final WeeklyPlanTotalsRecalculator weeklyPlanTotalsRecalculator;
    private final UserClock clock;

    public TaskService(TaskRepository taskRepository, TaskValidator validator,
                       ProjectRepository projectRepository, ProjectAutoCloseEvaluator autoCloseEvaluator,
                       TaskCategoryChecker categoryChecker, PlanBlockStatusMirror planBlockStatusMirror,
                       WeeklyPlanTotalsRecalculator weeklyPlanTotalsRecalculator, UserClock clock) {
        this.taskRepository = taskRepository;
        this.validator = validator;
        this.projectRepository = projectRepository;
        this.autoCloseEvaluator = autoCloseEvaluator;
        this.categoryChecker = categoryChecker;
        this.planBlockStatusMirror = planBlockStatusMirror;
        this.weeklyPlanTotalsRecalculator = weeklyPlanTotalsRecalculator;
        this.clock = clock;
    }

    /**
     * 태스크 생성 (PROJ-17 / AC-C-1~8). 검사 순서(service-sequences §2):
     * 평가 선행 → 404(projectId) → 422(CLOSED 가드) → 422(title·5분) → 404(categoryId) → save.
     *
     * <p><b>평가 선행(D-10)</b>: 기한 경과 IN_PROGRESS 프로젝트가 CLOSED로 선반영되어야 CLOSED 가드가
     * stale 상태를 우회당하지 않는다. dueDate는 검증하지 않는다 — 과거 허용(D-11).
     */
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

    /**
     * 프로젝트 내 태스크 목록 (PROJ-16 / EP-1 · AC-L-1~4). 정렬은 서버 고정(created_at DESC, task_id DESC — D-9).
     *
     * <p>읽기 경로 — 자동종료 평가·서비스 tx 없음(프로젝트 status를 응답·판정에 미사용, B-5 비소비 지점).
     * CLOSED/PAUSED 프로젝트의 태스크도 조회 가능(D-10은 쓰기 가드만). 프로젝트 소유는
     * {@code ProjectRepository.findByIdAndUserId}로 선판정해 부재·타인을 404로 은닉한다(AC-L-4).
     *
     * @param query status(단건 열거값, null/빈 값=전체 · 미정의값→422)·page(1-base)·size(≤100)
     */
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

    /**
     * 미배치 태스크 조회 (PROJ-19 / EP-7 · AC-U-1~4). 사용자 전체(전 프로젝트) 스코프 + 프로젝트명 조인(D-5a).
     * IN_PROGRESS 프로젝트의 UNASSIGNED 태스크만(D-5b — PAUSED·CLOSED 제외).
     *
     * <p><b>평가 경유 조회 의무</b>(TB-5): 조회 전 {@code closeOverdue} 선행 — 기한 경과 stale IN_PROGRESS 프로젝트가
     * CLOSED로 선반영되어야 그 프로젝트의 태스크가 노출되지 않는다(AC-U-2). status는 <b>생략 시 UNASSIGNED 기본값</b>
     * (D-5c 완화 — 유효값이 UNASSIGNED 하나뿐이라 생략을 기본값으로 수용). UNASSIGNED 외 값만 422 E-COM-009
     * (전용 라우트, 바인딩 500 회피 위해 String 수신 후 서비스 검증).
     */
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

    /**
     * 태스크 단건 조회 (PROJ-18 편집 폼 로딩 / EP-3 · AC-R-1~2). 전 필드 + version 반환.
     *
     * <p>평가 선행·서비스 tx 없음(service-sequences §1): 프로젝트 status를 판정에 쓰지 않고 404만 내므로
     * 자동종료 평가가 불요하다. 소유 체인 조인으로 부재·타인 소유를 404 E-COM-004로 은닉한다(AC-R-2).
     */
    public TaskResponse detail(UUID userId, UUID taskId) {
        OwnedTask owned = taskRepository.findOwnedWithProjectStatus(taskId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (AC-R-2)
        return TaskResponse.from(owned.task());
    }

    /**
     * 태스크 편집 단일 폼 (PROJ-18=PLAN-10 / EP-4 · AC-E-1~5). 6필드 전체 폼 교체 + 낙관락.
     * 검사 순서(service-sequences §3): 평가 선행 → 404(taskId) → 422(CLOSED) → 409(version) → 422(필드) → 404(categoryId).
     *
     * <p><b>CLOSED를 version보다 먼저</b>(as-built 순서 승계): "종료된 프로젝트 하위는 수정 불가"는 버전 신선도와
     * 무관한 절대 규칙(D-10). <b>COMPLETED 태스크 편집은 허용</b>(AC-E-5) — 태스크 status는 편집 가드가 아니다.
     * categoryId=null = 카테고리 해제(검사 생략). flush로 @Version 증가를 응답에 반영(FE 재조회 불요).
     */
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
        if (req.version() != task.getVersion()) { // 409 — 최신 TaskResponse 동봉 (AC-E-4)
            throw new OpenPlanException(ErrorCode.E_COM_006,
                    Map.of("latest", TaskResponse.from(task)));
        }

        String title = validator.validateTitle(req.title());          // 422 (AC-E-3, 생성과 동일 코드)
        validator.validateEstimatedMinutes(req.estimatedMinutes());    // 422
        validator.validatePriority(req.priority());                    // 422 — 생성과 동일 (AC-E-3)

        if (req.categoryId() != null && !categoryChecker.existsOwned(req.categoryId(), userId)) {
            throw new OpenPlanException(ErrorCode.E_COM_004);           // 404 (AC-E-3 categoryId, D-8)
        }

        task.edit(title, req.memo(), req.estimatedMinutes(), req.priority(), req.dueDate(), req.categoryId());
        taskRepository.flush(); // @Version 증가를 응답에 반영. 잔여 경합 → OptimisticLockException → 409
        return TaskResponse.from(task);
    }

    /**
     * 완료/미완료 전환 (PLAN-13/14 / EP-5 · AC-S-1~6). 태스크 status 전환 + plan_blocks 미러를 <b>동일 tx</b>로(TB-1).
     * 검사 순서(service-sequences §4): 평가 선행 → 404 → 422(CLOSED) → <b>no-op 단락</b>(version보다 먼저) → 409 → 전이.
     *
     * <p><b>CLOSED 가드가 no-op보다 먼저</b>(AC-S-5 ∩ AC-S-6 교차 — exceptions §3): CLOSED 하위는 no-op 요청도 422.
     * 완료→COMPLETED 미러, 미완료→착지(블록≥1: IN_PROGRESS+SCHEDULED 미러 / 블록0: UNASSIGNED, 미러 미발동 — D-1c).
     */
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

    /**
     * 태스크 삭제 (TUT-07 / EP-6 · AC-D-1~4). hard delete(D-4b) — 연관 데이터는 DB FK cascade에 위임(TB-3).
     * 검사 순서: 평가 선행 → 404 → 422(CLOSED) → 수집 → delete → <b>flush</b> → 재계산. version 불요(D-4a).
     *
     * <p><b>C1 순서(필수)</b>: ① 삭제 전 영향 주차 수집(taskId 스코프 — ADR-006) → ② delete → ③ <b>명시적 flush</b>
     * (DELETE·cascade를 DB에 반영) → ④ 재계산(TB-4). flush 없이는 JdbcTemplate 재계산이 삭제 전 plan_blocks를
     * 합산해 캐시가 오염된다([T1] 값 단언). 태스크 status는 삭제 가드가 아니다 — CLOSED 프로젝트만 막는다(D-10).
     */
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
}
