package com.openplan.backend.task.repository;

import com.openplan.backend.task.domain.Task;
import com.openplan.backend.task.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    Page<Task> findByProjectIdAndStatus(UUID projectId, TaskStatus status, Pageable pageable);
}
