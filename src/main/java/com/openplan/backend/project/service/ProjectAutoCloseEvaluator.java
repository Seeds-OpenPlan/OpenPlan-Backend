package com.openplan.backend.project.service;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.project.domain.ProjectStatus;
import com.openplan.backend.project.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 자동 종료 지연 평가 (PROJ-08 / T7). 읽기·쓰기 유스케이스의 <b>첫 데이터 접근</b>으로 호출된다(P-1).
 *
 * <p><b>REQUIRES_NEW</b>: 호출한 요청이 실패(422/409 롤백)해도 이미 판정된 자동 종료는 영속 유지된다 —
 * "관측이 상태를 만든다"는 지연 평가에서 관측 결과가 요청 성패에 종속되면 B-5 소비자마다 결과가 달라진다.
 *
 * <p><b>B-5 공개 계약</b>: {@code projects.status}를 읽어 노출/판정하는 후속 도메인(DASH·NOTI·RB 등)은
 * 조회 전 이 메서드를 호출해야 stale IN_PROGRESS를 피한다. 이 공개 메서드 자체가 계약이다.
 */
@Component
public class ProjectAutoCloseEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ProjectAutoCloseEvaluator.class);

    private final ProjectRepository projectRepository;
    private final UserClock clock;

    public ProjectAutoCloseEvaluator(ProjectRepository projectRepository, UserClock clock) {
        this.projectRepository = projectRepository;
        this.clock = clock;
    }

    /**
     * 해당 사용자의 기한 경과 IN_PROGRESS 프로젝트를 CLOSED로 영속한다. 반환 = 종료된 건수(0 = no-op).
     * 멱등·결정적(WHERE가 상태 기반) — 재실행해도 0건.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int closeOverdue(UUID userId) {
        LocalDate today = clock.todayOf(userId);
        int closed = projectRepository.bulkCloseOverdue(
                userId, today, clock.now(), ProjectStatus.CLOSED, ProjectStatus.IN_PROGRESS);
        if (closed > 0) {
            // Q-F(수동/자동 구분 미저장)의 최소 운영 추적선 — traceId는 MDC로 동반된다.
            log.info("projectAutoClose userId={} closed={}", userId, closed);
        }
        return closed;
    }
}
