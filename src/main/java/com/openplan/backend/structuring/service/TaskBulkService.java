package com.openplan.backend.structuring.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.structuring.domain.TaskStructuringDraft;
import com.openplan.backend.structuring.dto.TaskBulkCreateRequest;
import com.openplan.backend.structuring.repository.TaskStructuringDraftRepository;
import com.openplan.backend.task.dto.TaskResponse;
import com.openplan.backend.task.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 태스크 일괄 생성 (RB-PROJ-01) — 구조화 초안 채택 경로.
 *
 * <p><b>생성 규칙을 다시 쓰지 않는다.</b> 단건 생성({@link TaskService#create})을 그대로 호출한다 —
 * 제목·예상시간 검증, 프로젝트 상태 가드(CLOSED → 422), 카테고리 소유 판정이 두 곳으로 갈라지면
 * 언젠가 어긋난다. 여기가 더하는 것은 <b>한 트랜잭션 · 초안 채택 표시</b> 둘뿐이다.
 *
 * <p><b>하나라도 실패하면 전체 롤백이다.</b> 절반만 생성된 태스크 목록은 사용자가 되돌리기 어렵다.
 */
@Service
public class TaskBulkService {

    private static final Logger log = LoggerFactory.getLogger(TaskBulkService.class);

    private final TaskService taskService;
    private final TaskStructuringDraftRepository draftRepository;

    public TaskBulkService(TaskService taskService, TaskStructuringDraftRepository draftRepository) {
        this.taskService = taskService;
        this.draftRepository = draftRepository;
    }

    @Transactional
    public List<TaskResponse> createAll(UUID userId, UUID projectId, TaskBulkCreateRequest request) {
        List<TaskResponse> created = new ArrayList<>();

        for (TaskBulkCreateRequest.Item item : request.tasks()) {
            created.add(taskService.create(userId, projectId, item.toCreateRequest()));
            markAdopted(projectId, item.draftId());
        }
        return List.copyOf(created);
    }

    /**
     * 초안 채택 표시. <b>남의 프로젝트 초안은 건드리지 않는다</b> — draftId 는 요청이 주는 값이라
     * 경로의 projectId 와 대조해야 한다. 어긋나면 조용히 넘어가되 흔적은 남긴다: 채택 표시가 실패해도
     * 태스크 생성 자체는 유효하고, 그것 때문에 사용자의 일괄 생성을 통째로 되돌리는 편이 더 나쁘다.
     */
    private void markAdopted(UUID projectId, UUID draftId) {
        if (draftId == null) {
            return;
        }
        TaskStructuringDraft draft = draftRepository.findById(draftId).orElse(null);
        if (draft == null || !draft.getProjectId().equals(projectId)) {
            log.warn("채택 표시 건너뜀 — 초안이 없거나 이 프로젝트 것이 아니다: draftId={} projectId={}",
                    draftId, projectId);
            return;
        }
        draft.markAdopted();
    }
}
