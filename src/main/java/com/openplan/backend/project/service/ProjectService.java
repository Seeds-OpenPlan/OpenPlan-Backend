package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.dto.ProjectCreateRequest;
import com.openplan.backend.project.dto.ProjectResponse;
import com.openplan.backend.project.dto.ProjectStatusChangeRequest;
import com.openplan.backend.project.dto.ProjectUpdateRequest;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.port.WeeklyPlanTotalsRecalculator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectValidator validator;
    private final ProjectAutoCloseEvaluator autoCloseEvaluator;
    private final WeeklyPlanTotalsRecalculator weeklyPlanTotalsRecalculator;
    private final UserClock clock;

    public ProjectService(ProjectRepository projectRepository, ProjectValidator validator,
                          ProjectAutoCloseEvaluator autoCloseEvaluator,
                          WeeklyPlanTotalsRecalculator weeklyPlanTotalsRecalculator, UserClock clock) {
        this.projectRepository = projectRepository;
        this.validator = validator;
        this.autoCloseEvaluator = autoCloseEvaluator;
        this.weeklyPlanTotalsRecalculator = weeklyPlanTotalsRecalculator;
        this.clock = clock;
    }

    /**
     * 프로젝트 생성 (PROJ-02 / AC-02-1~4). 신규 행은 평가 대상이 아니므로 평가 선행 불요.
     */
    @Transactional
    public ProjectResponse create(UUID userId, ProjectCreateRequest req) {
        LocalDate today = clock.todayOf(userId);
        String name = validator.validateName(req.name());
        validator.validateDueDate(req.dueDate(), today);
        validator.validatePriority(req.priority());                // 422 — 1·2·3만 (제품 3단계)

        Project project = new Project(userId, name, req.description(), req.dueDate(), req.priority(), clock.now());
        projectRepository.save(project);
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse update(UUID userId, UUID projectId, ProjectUpdateRequest req) {
        autoCloseEvaluator.closeOverdue(userId);

        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        if (project.getStatus() == ProjectStatus.CLOSED) { // 422 — 종료는 수정 불가, 재개 먼저(Q-E1). 편집 전용 코드(전이불허 E-PROJ-003과 구분)
            throw new OpenPlanException(ErrorCode.E_PROJ_005);
        }
        if (req.version() != project.getVersion()) { // 409 — 종료 아닌 경우의 동시수정 보호, latest 동봉(SYS-05)
            throw new OpenPlanException(ErrorCode.E_COM_006,
                    Map.of("latest", ProjectResponse.from(project)));
        }

        LocalDate today = clock.todayOf(userId);
        String name = validator.validateName(req.name());          // 422 — 생성과 동일 규칙(AC-06-2)

        boolean requestKeepsPastDue = req.dueDate() != null && req.dueDate().isBefore(today);
        boolean projectAlreadyOverdue = project.getDueDate() != null && project.getDueDate().isBefore(today);
        if (requestKeepsPastDue && projectAlreadyOverdue) {
            throw new OpenPlanException(ErrorCode.E_PROJ_006);
        }
        validator.validateDueDate(req.dueDate(), today);
        validator.validatePriority(req.priority());                // 422 — 생성과 동일 (AC-06-2)

        project.edit(name, req.description(), req.dueDate(), req.priority());
        projectRepository.flush();
        return ProjectResponse.from(project);
    }

    public Page<ProjectResponse> list(UUID userId, int page, int size, List<String> statusRaw) {
        Collection<ProjectStatus> statuses = parseStatuses(statusRaw);

        autoCloseEvaluator.closeOverdue(userId); // 평가 선행(REQUIRES_NEW 커밋) → 이후 조회가 결과를 봄

        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return projectRepository.findByUserIdAndStatusIn(userId, statuses, pageable)
                .map(ProjectResponse::from);
    }

    public ProjectResponse detail(UUID userId, UUID projectId) {
        autoCloseEvaluator.closeOverdue(userId);
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        return ProjectResponse.from(project);
    }

    @Transactional
    public ProjectResponse changeStatus(UUID userId, UUID projectId, ProjectStatusChangeRequest req) {
        ProjectStatus target = parseStatus(req.getStatus()); // 422 E-COM-009 (미정의 열거값)
        autoCloseEvaluator.closeOverdue(userId);

        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        if (project.getStatus() == target) {   // no-op: version 미증가·dueDate 무시 (AC-07-3)
            return ProjectResponse.from(project);
        }
        if (req.getVersion() != project.getVersion()) { // 409 (AC-07-5)
            throw new OpenPlanException(ErrorCode.E_COM_006, Map.of("latest", ProjectResponse.from(project)));
        }
        if (!project.getStatus().canTransitionTo(target)) { // T6 CLOSED→PAUSED → 422 (AC-07-2)
            throw new OpenPlanException(ErrorCode.E_PROJ_003);
        }

        if (target == ProjectStatus.IN_PROGRESS) {          // 재개 (T3/T5) + G-1 가드 (Q-C2)
            LocalDate today = clock.todayOf(userId);
            if (req.isDueDateProvided()) {
                validator.validateDueDate(req.getDueDate(), today); // 동반값이 과거면 422 E-COM-009
            }
            LocalDate effectiveDue = req.isDueDateProvided() ? req.getDueDate() : project.getDueDate();
            if (effectiveDue != null && effectiveDue.isBefore(today)) { // 과거 유지한 채 재개 → E-PROJ-004
                throw new OpenPlanException(ErrorCode.E_PROJ_004);
            }
            project.resume(req.getDueDate(), req.isDueDateProvided(), clock.now());
        } else {                                            // T1(→PAUSED)·T2/T4(→CLOSED)
            if (req.isDueDateProvided()) {                  // dueDate는 재개 전용
                throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("fields", List.of(Map.of(
                        "field", "dueDate", "rule", "unexpected", "message", "마감일은 재개 시에만 함께 변경할 수 있습니다."))));
            }
            project.transitionTo(target, clock.now());
        }

        projectRepository.flush(); // @Version 증가를 응답에 반영
        return ProjectResponse.from(project);
    }

    /** status 문자열 → enum. 미정의 값 → 422 E-COM-009 (전이 오류 E-PROJ-003과 구분). */
    private ProjectStatus parseStatus(String raw) {
        try {
            return ProjectStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("fields", List.of(Map.of(
                    "field", "status", "rule", "enum", "message", "정의되지 않은 상태값입니다: " + raw))));
        }
    }

    @Transactional
    public void delete(UUID userId, UUID projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (부재·타인·재삭제)

        List<UUID> affectedWeeklyPlans = weeklyPlanTotalsRecalculator.captureAffectedWeeklyPlanIds(projectId);
        projectRepository.delete(project);
        projectRepository.flush(); // C1 — cascade가 DB에 반영되어야 아래 재계산이 삭제 후 상태를 본다
        weeklyPlanTotalsRecalculator.recalculate(affectedWeeklyPlans); // B-4, 같은 tx
    }


    /** status 필터 파싱(Q-H). null/빈 값 = 전체. 미정의 열거값 → 422 E-COM-009. */
    private Collection<ProjectStatus> parseStatuses(List<String> statusRaw) {
        if (statusRaw == null || statusRaw.isEmpty()) {
            return EnumSet.allOf(ProjectStatus.class);
        }
        EnumSet<ProjectStatus> set = EnumSet.noneOf(ProjectStatus.class);
        for (String raw : statusRaw) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                set.add(ProjectStatus.valueOf(raw.trim()));
            } catch (IllegalArgumentException ex) {
                throw new OpenPlanException(ErrorCode.E_COM_009,
                        Map.of("fields", List.of(Map.of(
                                "field", "status", "rule", "enum", "message", "정의되지 않은 상태값입니다: " + raw))));
            }
        }
        return set.isEmpty() ? EnumSet.allOf(ProjectStatus.class) : set;
    }
}
