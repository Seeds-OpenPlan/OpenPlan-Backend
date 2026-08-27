package com.openplan.backend.structuring.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.Project;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.ProjectAutoCloseEvaluator;
import com.openplan.backend.structuring.dto.StructureWarningResponse;
import com.openplan.backend.task.repository.TaskRepository;
import com.openplan.backend.task.repository.TaskStructureCounts;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 구조 부족 경고 조회 (SS-04 / RB-PROJ-02). <b>사실 수집만 하고 판정은 하지 않는다</b> —
 * 임계값 분기는 전부 {@link StructureWarningPolicy}에 있다(두 곳으로 갈라지면 골든과 드리프트).
 *
 * <p>읽기 전용이다. 저장·부작용이 없고, 유일한 쓰기는 아래 자동 종료 평가의 커밋뿐이다.
 */
@Service
public class StructureWarningService {

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final ProjectAutoCloseEvaluator autoCloseEvaluator;
    private final UserClock clock;

    public StructureWarningService(ProjectRepository projectRepository, TaskRepository taskRepository,
                                   ProjectAutoCloseEvaluator autoCloseEvaluator, UserClock clock) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.autoCloseEvaluator = autoCloseEvaluator;
        this.clock = clock;
    }

    /**
     * 경고 판정 (getStructureWarnings). 경고가 없으면 빈 배열(200) — 오류가 아니다.
     *
     * <p><b>자동 종료 평가가 먼저다</b>(B-5 · 헌법 패턴 12). 이 판정은 {@code projects.status}와
     * dueDate-대-today를 소비하는데, 평가 없이는 어제 마감된 stale IN_PROGRESS가 남아 "마감까지 -1일"
     * 류의 좀비 경고를 만든다. 평가 후 그 프로젝트는 CLOSED로 판정돼 빈 배열이 된다.
     *
     * <p>tx 애너테이션을 두지 않는다 — 평가(REQUIRES_NEW 커밋) → 저장소 내장 읽기 tx의 2-tx 패턴으로,
     * {@code ProjectService.detail}의 as-built와 같다. 부재·타인 → 404(구분 불가, 존재 은닉).
     */
    public List<StructureWarningResponse> warnings(UUID userId, UUID projectId) {
        autoCloseEvaluator.closeOverdue(userId); // B-5 — stale IN_PROGRESS를 먼저 걷어낸다

        Project project = projectRepository.findByIdAndUserId(projectId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004)); // 404 (부재·타인)

        LocalDate today = clock.todayOf(userId); // P-2 — 사용자 타임존 기준(월경계)

        // 세 숫자를 한 스냅샷에서 센다 — 따로 세면 그 사이 태스크 변경이 자기모순 문구를 만든다
        // (근거는 TaskRepository.countStructure 참고).
        TaskStructureCounts counts = taskRepository.countStructure(projectId);

        return StructureWarningPolicy.evaluate(project.getStatus(), project.getDueDate(), today,
                counts.total(), counts.remaining(), counts.missingEstimates());
    }
}
