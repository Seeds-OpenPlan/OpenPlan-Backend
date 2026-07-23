package com.openplan.backend.task.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.ProjectAutoCloseEvaluator;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.dto.TaskCreateRequest;
import com.openplan.backend.task.dto.TaskResponse;
import com.openplan.backend.task.repository.OwnedTask;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.service.port.TaskCategoryChecker;
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
    private final UserClock clock;

    public TaskService(TaskRepository taskRepository, TaskValidator validator,
                       ProjectRepository projectRepository, ProjectAutoCloseEvaluator autoCloseEvaluator,
                       TaskCategoryChecker categoryChecker, UserClock clock) {
        this.taskRepository = taskRepository;
        this.validator = validator;
        this.projectRepository = projectRepository;
        this.autoCloseEvaluator = autoCloseEvaluator;
        this.categoryChecker = categoryChecker;
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
            throw new OpenPlanException(ErrorCode.E_PROJ_003,
                    Map.of("fields", List.of(Map.of("field", "project.status"))));
        }

        String title = validator.validateTitle(req.title());          // 422 (AC-C-3)
        validator.validateEstimatedMinutes(req.estimatedMinutes());    // 422 (AC-C-4)

        if (req.categoryId() != null && !categoryChecker.existsOwned(req.categoryId(), userId)) {
            throw new OpenPlanException(ErrorCode.E_COM_004);           // 404 (AC-C-5, D-8)
        }

        Task task = new Task(projectId, req.categoryId(), title, req.memo(),
                req.estimatedMinutes(), req.priority(), req.dueDate(), clock.now());
        taskRepository.save(task);
        return TaskResponse.from(task);
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
}
