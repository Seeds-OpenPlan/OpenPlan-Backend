package com.openplan.backend.task.repository;

import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 태스크 저장소 (ST-B2-03). 소유는 tasks→projects.user_id 조인으로 판정한다(tasks에 user_id 없음 — D-16).
 *
 * <p>조회 계열 나머지(프로젝트 내 목록·미배치)는 후속 슬라이스에서 code-structure §4 설계대로 추가한다.
 */
public interface TaskRepository extends JpaRepository<Task, UUID> {
    @Query("""
            select new com.openplan.backend.task.repository.OwnedTask(t, p.status)
              from Task t, Project p
             where t.id = :taskId and p.id = t.projectId and p.userId = :userId
            """)
    Optional<OwnedTask> findOwnedWithProjectStatus(@Param("taskId") UUID taskId, @Param("userId") UUID userId);

    Page<Task> findByProjectId(UUID projectId, Pageable pageable);

    /** 프로젝트의 태스크 수 (복제 프리뷰 — PROJ-11). */
    long countByProjectId(UUID projectId);

    Page<Task> findByProjectIdAndStatus(UUID projectId, TaskStatus status, Pageable pageable);

    /**
     * EP-7 미배치 — 사용자 전체(전 프로젝트) 스코프 + IN_PROGRESS 프로젝트 조인(D-5b) + 프로젝트명(D-5a).
     * Pageable은 페이지네이션(LIMIT/OFFSET)만 담당한다. 프로젝션 쿼리는 count 자동 유도 불가 → countQuery 명시.
     */
    @Query(value = """
            select new com.openplan.backend.task.repository.UnassignedTaskRow(t, p.name)
              from Task t, Project p
             where p.id = t.projectId and p.userId = :userId
               and p.status = com.openplan.backend.project.domain.ProjectStatus.IN_PROGRESS
               and t.status = com.openplan.backend.task.domain.TaskStatus.UNASSIGNED
             order by t.createdAt desc, t.id desc
            """,
            countQuery = """
            select count(t)
              from Task t, Project p
             where p.id = t.projectId and p.userId = :userId
               and p.status = com.openplan.backend.project.domain.ProjectStatus.IN_PROGRESS
               and t.status = com.openplan.backend.task.domain.TaskStatus.UNASSIGNED
            """)
    Page<UnassignedTaskRow> findUnassignedWithProjectName(@Param("userId") UUID userId, Pageable pageable);

    /**
     * DASH(대시보드) 미배치 카운트 — {@link #findUnassignedWithProjectName}과 같은 스코프(D-5b: 사용자
     * 전체·IN_PROGRESS 프로젝트)를 count 전용으로 재사용한다(목록 조립 없이 개수만 필요한 RiskIssue/
     * priorityAction 판정용, ST-B2-15).
     */
    @Query("""
            select count(t)
              from Task t, Project p
             where p.id = t.projectId and p.userId = :userId
               and p.status = com.openplan.backend.project.domain.ProjectStatus.IN_PROGRESS
               and t.status = com.openplan.backend.task.domain.TaskStatus.UNASSIGNED
            """)
    long countUnassigned(@Param("userId") UUID userId);

    /**
     * "마감 임박" 공용 정의(us-decisions-kr.md §5.1 — DEADLINE_SOON_DAYS=3) 적용 카운트. 미완료
     * (status &lt;&gt; COMPLETED) + dueDate가 [today, threshold] 구간(양끝 포함). 프로젝트 상태 무관 —
     * 정의가 "미완료 태스크(또는 소속 프로젝트)"라 IN_PROGRESS 스코프를 두지 않는다(미배치와 다른 점).
     */
    @Query("""
            select count(t)
              from Task t
             where t.status <> com.openplan.backend.task.domain.TaskStatus.COMPLETED
               and t.dueDate is not null and t.dueDate >= :today and t.dueDate <= :threshold
               and exists (select 1 from Project p where p.id = t.projectId and p.userId = :userId)
            """)
    long countDeadlineSoon(@Param("userId") UUID userId, @Param("today") LocalDate today,
                           @Param("threshold") LocalDate threshold);

    /** DASH-06 HAS_UNASSIGNED 배지 — N+1 회피를 위해 대상 프로젝트 id를 한 번에 배치 조회한다. */
    @Query("""
            select distinct t.projectId
              from Task t, Project p
             where p.id = t.projectId and p.userId = :userId
               and t.status = com.openplan.backend.task.domain.TaskStatus.UNASSIGNED
            """)
    List<UUID> findProjectIdsWithUnassignedTasks(@Param("userId") UUID userId);
}
