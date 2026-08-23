package com.openplan.backend.structuring.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.structuring.domain.TaskStructuringDraft;
import com.openplan.backend.structuring.dto.StructuringDraftResponse;
import com.openplan.backend.structuring.repository.TaskStructuringDraftRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 구조화 초안 생성 (SS-03 / RB-PROJ-01).
 *
 * <p><b>초안만 만든다</b>(C-2). 태스크는 사용자가 {@code POST /projects/{id}/tasks/bulk} 로 채택할 때
 * 비로소 생긴다 — 여기서 만든 것은 제안일 뿐이라 프로젝트의 태스크 목록에 나타나지 않는다.
 *
 * <p><b>재생성은 전면 교체다.</b> 다시 누르면 아직 채택 안 된 초안을 지우고 새로 만든다. 규칙 사전이
 * 정적이라 같은 이름이면 같은 결과가 나오지만, 프로젝트명을 고친 뒤 다시 누르는 경우가 있어
 * 누적되지 않게 한다. <b>채택된 것은 남긴다</b> — 그건 제안이 아니라 이력이다.
 */
@Service
public class StructuringDraftService {

    private final ProjectRepository projectRepository;
    private final TaskStructuringDraftRepository draftRepository;
    private final StructuringDictionary dictionary;
    private final UserClock clock;

    public StructuringDraftService(ProjectRepository projectRepository,
                                   TaskStructuringDraftRepository draftRepository,
                                   StructuringDictionary dictionary, UserClock clock) {
        this.projectRepository = projectRepository;
        this.draftRepository = draftRepository;
        this.dictionary = dictionary;
        this.clock = clock;
    }

    /** 초안 생성 (201). 부재·타인 프로젝트 → 404(존재 은닉). 프로젝트 상태는 보지 않는다 — 제안은 저장이 아니다. */
    @Transactional
    public List<StructuringDraftResponse> generate(UUID userId, UUID projectId) {
        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404

        StructuringDictionary.Match match = dictionary.match(project.getName());

        draftRepository.deleteByProjectIdAndAdoptedFalse(projectId);
        draftRepository.flush(); // 지우고 나서 넣는다 — 같은 tx 안에서 순서가 뒤집히면 중복이 남는다

        List<TaskStructuringDraft> saved = match.drafts().stream()
                .map(d -> new TaskStructuringDraft(projectId, d.title(),
                        d.estimatedMinutes(), d.priority(), clock.now()))
                .map(draftRepository::save)
                .toList();

        String reason = match.reason();
        return saved.stream().map(d -> StructuringDraftResponse.of(d, reason)).toList();
    }
}
