package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.dto.DuplicateProjectRequest;
import com.openplan.backend.project.dto.DuplicationPreviewResponse;
import com.openplan.backend.project.dto.ProjectResponse;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.domain.WbsItem;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.repository.WbsItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 프로젝트 복제 유스케이스 (PROJ-10·11·12). 복제 프리뷰(개요 확인)와 복제 실행을 한 관심사로 묶는다.
 *
 * <p>본 슬라이스는 <b>프리뷰(조회)</b>만. 복제 실행(POST /duplications)은 후속 — 이 프리뷰가 정한
 * "무엇을 복제하는가"(태스크·WBS, 주간 계획 항목 제외)를 그대로 따른다.
 */
@Service
public class ProjectDuplicationService {

    /** 프리뷰 안내(P4 — 사실 서술). 복제본 태스크가 미배치로 생성됨을 미리 알린다(정본 note). */
    private static final String NOTE = "주간 계획에 배치된 항목은 복제되지 않습니다 — 복제본의 태스크는 전량 미배치로 생성됩니다.";

    private static final Logger log = LoggerFactory.getLogger(ProjectDuplicationService.class);

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final WbsItemRepository wbsItemRepository;
    private final ProjectValidator validator;
    private final UserClock clock;

    public ProjectDuplicationService(ProjectRepository projectRepository, TaskRepository taskRepository,
                                     WbsItemRepository wbsItemRepository, ProjectValidator validator,
                                     UserClock clock) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.wbsItemRepository = wbsItemRepository;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * 복제 프리뷰 (getDuplicationPreview). 복제 시 딸려 올 항목 개요(이름·설명·태스크 수·WBS 수)를 반환한다.
     * 프리뷰는 조회라 프로젝트 상태(CLOSED/PAUSED) 무관하게 허용한다. 부재·타인 → 404. 읽기 tx.
     */
    @Transactional(readOnly = true)
    public DuplicationPreviewResponse preview(UUID userId, UUID projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (부재·타인)

        long taskCount = taskRepository.countByProjectId(projectId);
        long wbsItemCount = wbsItemRepository.countByProjectId(projectId);

        return new DuplicationPreviewResponse(
                project.getName(), project.getDescription(), taskCount, wbsItemCount, NOTE);
    }

    /**
     * 복제 실행 (duplicateProject). 프로젝트+태스크+WBS를 새 id로 통째 복사한다(한 tx, 원자적).
     * 복제본 프로젝트는 IN_PROGRESS, 태스크는 전량 UNASSIGNED. 주간계획 블록·수행이력은 복사하지 않는다.
     * 원본은 무변경. 부재·타인 → 404.
     *
     * <p><b>Idempotency-Key</b>: 복제는 자연 멱등이 아니라 더블클릭 시 복제본이 둘 생길 수 있다(확정과 다름 —
     * 확정은 상태 멱등). 서버 강제 dedup(키 저장소)은 전 도메인 공통 인프라라 이 슬라이스 범위 밖이며,
     * 키는 관측용 로그로만 남긴다(확정과 동일 관례). 이중 생성 방지는 후속 과제.
     */
    @Transactional
    public ProjectResponse duplicate(UUID userId, UUID projectId, DuplicateProjectRequest request,
                                     String idempotencyKey) {
        Project source = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (부재·타인)

        Instant now = clock.now();
        String newName = resolveNewName(request, source.getName());

        // 1) 프로젝트 복제 — 새 id, IN_PROGRESS, description 복사(마감일·우선순위는 원본 값 승계).
        Project copy = new Project(userId, newName, source.getDescription(),
                source.getDueDate(), source.getPriority(), now);
        projectRepository.save(copy);

        // 2) 태스크 복제 — 전량 UNASSIGNED. oldTaskId → newTaskId 매핑(WBS 재연결용).
        Map<UUID, UUID> taskIdMap = new HashMap<>();
        for (Task src : taskRepository.findByProjectIdOrderByIdAsc(projectId)) {
            Task dup = new Task(copy.getId(), src.getCategoryId(), src.getTitle(), src.getMemo(),
                    src.getEstimatedMinutes(), src.getPriority(), src.getDueDate(), now);
            taskRepository.save(dup);
            taskIdMap.put(src.getId(), dup.getId());
        }

        // 3) WBS 복제 — 새 project_id + 매핑된 새 task_id로 재연결.
        for (WbsItem src : wbsItemRepository.findByProjectId(projectId)) {
            UUID newTaskId = taskIdMap.get(src.getTaskId());
            if (newTaskId == null) {
                continue; // 이론상 불가(WBS는 태스크에 종속) — 방어
            }
            wbsItemRepository.save(WbsItem.create(copy.getId(), newTaskId, src.getStartDate(), src.getEndDate(), now));
        }

        log.info("project duplicated: sourceId={}, newId={}, userId={}, idempotencyKey={}",
                projectId, copy.getId(), userId, idempotencyKey);
        return ProjectResponse.from(copy);
    }

    /** newName 미지정(null·공백) → "원본명 (복제)". 지정 시 생성과 동일 규칙으로 검증(422). */
    private String resolveNewName(DuplicateProjectRequest request, String sourceName) {
        if (request == null || request.newName() == null || request.newName().isBlank()) {
            return sourceName + " (복제)";
        }
        return validator.validateName(request.newName());
    }
}
