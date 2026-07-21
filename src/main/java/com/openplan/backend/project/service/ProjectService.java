package com.openplan.backend.project.service;

import com.openplan.backend.project.dto.ProjectCreateRequest;
import com.openplan.backend.project.dto.ProjectResponse;
import com.openplan.backend.project.entity.Project;
import com.openplan.backend.project.repository.ProjectRepository;
import com.openplan.backend.project.service.port.UserClock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 프로젝트 유스케이스 파사드 — 검사 순서·tx 경계를 소유한다. 검증 규칙은 {@link ProjectValidator},
 * 시각 소스는 {@link UserClock}, 영속은 {@link ProjectRepository}에 위임한다.
 *
 * <p>조회·상태변경·삭제·자동종료는 이후 슬라이스에서 추가한다(생성 C 슬라이스).
 */
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectValidator validator;
    private final UserClock clock;

    public ProjectService(ProjectRepository projectRepository, ProjectValidator validator, UserClock clock) {
        this.projectRepository = projectRepository;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * 프로젝트 생성 (PROJ-02 / AC-02-1~4). status=IN_PROGRESS·closedAt=null·version=0은 엔티티 생성자가 규정.
     * 신규 행은 자동종료 평가 대상이 아니므로 평가 선행 불요(과거 due_date는 검증으로 사전 차단).
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
}
