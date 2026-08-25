package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.dto.DuplicationPreviewResponse;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.repository.WbsItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final WbsItemRepository wbsItemRepository;

    public ProjectDuplicationService(ProjectRepository projectRepository, TaskRepository taskRepository,
                                     WbsItemRepository wbsItemRepository) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.wbsItemRepository = wbsItemRepository;
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
}
