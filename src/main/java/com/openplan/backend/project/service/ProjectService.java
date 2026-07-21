package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.dto.ProjectCreateRequest;
import com.openplan.backend.project.dto.ProjectResponse;
import com.openplan.backend.project.dto.ProjectStatusChangeRequest;
import com.openplan.backend.project.dto.ProjectUpdateRequest;
import com.openplan.backend.project.entity.Project;
import com.openplan.backend.project.entity.ProjectStatus;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.port.WeeklyPlanTotalsRecalculator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 프로젝트 유스케이스 파사드 — 검사 순서·tx 경계를 소유한다. 검증은 {@link ProjectValidator},
 * 시각은 {@link UserClock}, 지연 평가는 {@link ProjectAutoCloseEvaluator}, 영속은 {@link ProjectRepository}.
 *
 * <p>조회(list/detail)는 @Transactional을 붙이지 않는다 — 평가(REQUIRES_NEW) 커밋 후 repository 내장
 * readOnly tx로 조회하는 2-tx 구조(service-sequences §1). readOnly tx에서는 평가 UPDATE를 할 수 없기 때문.
 */
@Service
public class ProjectService {

    private static final int SIZE_MAX = 100;

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

        Project project = new Project(userId, name, req.description(), req.dueDate(), req.priority(), clock.now());
        projectRepository.save(project);
        return ProjectResponse.from(project);
    }

    /**
     * 프로젝트 편집 (PROJ-06 / AC-06-1~4). 검사 순서 <b>404 → 422(CLOSED) → 409(버전) → 422(필드)</b>.
     * 평가 선행(P-1): 기한 경과였다면 여기서 CLOSED·version+1 된다.
     *
     * <p><b>CLOSED를 버전보다 먼저 검사</b>(User 판정 2026-07-22, 원설계의 버전-우선을 대체): "종료된 건 수정 불가"는
     * 버전 신선도와 무관한 절대 규칙이라, 자동종료가 버전을 올려도 "충돌(409)"이 아니라 "종료라 수정 불가(422)"로
     * 명확히 안내한다. 종료 아닌 경우의 동시 수정 보호(409)는 그대로 유지된다.
     */
    @Transactional
    public ProjectResponse update(UUID userId, UUID projectId, ProjectUpdateRequest req) {
        autoCloseEvaluator.closeOverdue(userId);

        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        if (project.getStatus() == ProjectStatus.CLOSED) { // 422 — 종료는 수정 불가, 재개 먼저(Q-E1)
            throw new OpenPlanException(ErrorCode.E_PROJ_003);
        }
        if (req.version() != project.getVersion()) { // 409 — 종료 아닌 경우의 동시수정 보호, latest 동봉(SYS-05)
            throw new OpenPlanException(ErrorCode.E_COM_006,
                    Map.of("latest", ProjectResponse.from(project)));
        }

        LocalDate today = clock.todayOf(userId);
        String name = validator.validateName(req.name());          // 422 — 생성과 동일 규칙(AC-06-2)
        validator.validateDueDate(req.dueDate(), today);

        project.edit(name, req.description(), req.dueDate(), req.priority());
        // 명시적 flush: @Version이 여기서 증가(WHERE version=? UPDATE)해 응답이 새 version을 담는다
        // (AC-06-1 "version 증가 반영 — FE 재조회 불요"). 잔여 경합은 OptimisticLockException → 409.
        projectRepository.flush();
        return ProjectResponse.from(project);
    }

    /**
     * 목록 조회 (PROJ-01 / AC-01-1~7). 조회 전 자동종료 평가 선행(AC-01-6). status 필터는 그룹/개별
     * 겸용(Q-H), 정렬은 createdAt DESC·id DESC 서버 고정(Q-I).
     *
     * @param statusRaw null/빈 값 = 전체 3상태. 미정의 열거값 → 422.
     */
    public Page<ProjectResponse> list(UUID userId, int page, int size, List<String> statusRaw) {
        validatePaging(page, size);
        Collection<ProjectStatus> statuses = parseStatuses(statusRaw);

        autoCloseEvaluator.closeOverdue(userId); // 평가 선행(REQUIRES_NEW 커밋) → 이후 조회가 결과를 봄

        Pageable pageable = PageRequest.of(page - 1, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return projectRepository.findByUserIdAndStatusIn(userId, statuses, pageable)
                .map(ProjectResponse::from);
    }

    /**
     * 상세 조회 (PROJ-03 / AC-03-1~3). 평가 선행(AC-03-3). 부재·타인 소유 → 404 E-COM-004(구분 불가).
     */
    public ProjectResponse detail(UUID userId, UUID projectId) {
        autoCloseEvaluator.closeOverdue(userId);
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        return ProjectResponse.from(project);
    }

    /**
     * 프로젝트 상태 변경 (PROJ-07 / AC-07-1~5). 순서: 열거 파싱(422) → 평가 선행 → 404 →
     * <b>no-op 단락</b>(version 검사보다 먼저 — 더블클릭 내성, AC-07-3) → 409 → 전이 검증(T6→422) → 전이 수행.
     */
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

    /**
     * 프로젝트 삭제 (PROJ-09 / AC-09-1~6). hard delete(Q-D) — 연관 데이터는 DB FK cascade에 위임(B-3).
     * 상태 무관(AC-09-4), version 불요(FE 확인창이 방어선). 자동종료 평가는 결과에 영향 없어 생략.
     *
     * <p><b>C1 순서(필수)</b>: ① 삭제 전 영향 주차 수집(B-4) → ② delete → ③ <b>명시적 flush</b>(DELETE·cascade를
     * DB에 반영) → ④ 재계산. flush 없이는 JdbcTemplate 재계산이 삭제 전 plan_blocks를 합산해 캐시가 오염된다.
     */
    @Transactional
    public void delete(UUID userId, UUID projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (부재·타인·재삭제)

        List<UUID> affectedWeeklyPlans = weeklyPlanTotalsRecalculator.captureAffectedWeeklyPlanIds(projectId);
        projectRepository.delete(project);
        projectRepository.flush(); // C1 — cascade가 DB에 반영되어야 아래 재계산이 삭제 후 상태를 본다
        weeklyPlanTotalsRecalculator.recalculate(affectedWeeklyPlans); // B-4, 같은 tx
    }

    /** page/size 규약 위반은 구조 오류(400 E-COM-001). AC-01-4. */
    private void validatePaging(int page, int size) {
        List<Map<String, Object>> bad = new ArrayList<>();
        if (page < 1) {
            bad.add(Map.of("field", "page", "rule", "min", "message", "page는 1 이상이어야 합니다."));
        }
        if (size < 1 || size > SIZE_MAX) {
            bad.add(Map.of("field", "size", "rule", "range", "message", "size는 1~100이어야 합니다."));
        }
        if (!bad.isEmpty()) {
            throw new OpenPlanException(ErrorCode.E_COM_001, Map.of("fields", bad));
        }
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
