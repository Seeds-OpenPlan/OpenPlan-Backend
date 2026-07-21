package com.openplan.backend.project.repository;

import com.openplan.backend.project.entity.Project;
import com.openplan.backend.project.entity.ProjectStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 프로젝트 저장소 (ST-B2-01). 전 쿼리는 user_id 스코프(소유자 격리·404 은닉).
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** 소유자 스코프 단건 — 상세/편집/삭제 공용. 부재 → 404 E-COM-004. */
    Optional<Project> findByIdAndUserId(UUID projectId, UUID userId);

    /**
     * 목록: status 필터 + 페이지. 정렬은 서비스가 Pageable에 고정 주입한다
     * (createdAt DESC, id DESC — 결정적, Q-I).
     */
    Page<Project> findByUserIdAndStatusIn(UUID userId, Collection<ProjectStatus> statuses, Pageable pageable);

    /**
     * 자동 종료 지연 평가 (PROJ-08 / T7) — 후보 판정·영속을 단문 UPDATE로. 반환 = 종료된 건수.
     *
     * <p>후보를 개별 로드하지 않는 이유: N+1·개별 낙관락 충돌로 비결정적 부분 실패가 생긴다.
     * 단문 조건 UPDATE는 원자·멱등(WHERE가 상태 기반)이라 결정성(AC-08-6)이 구조로 보장된다.
     * {@code version = version + 1} 수동 증가로 자동 전이도 동시 편집과 같은 version 축에서 직렬화한다(SSM §1).
     * 제외: PAUSED(Q-B2)·무기한 null(Q-B1)·이미 CLOSED(WHERE status=IN_PROGRESS). 당일 미포함(AC-08-2).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Project p
               set p.status = :closed, p.closedAt = :now, p.version = p.version + 1
             where p.userId = :userId
               and p.status = :inProgress
               and p.dueDate is not null
               and p.dueDate < :today
            """)
    int bulkCloseOverdue(@Param("userId") UUID userId,
                         @Param("today") LocalDate today,
                         @Param("now") Instant now,
                         @Param("closed") ProjectStatus closed,
                         @Param("inProgress") ProjectStatus inProgress);
}
