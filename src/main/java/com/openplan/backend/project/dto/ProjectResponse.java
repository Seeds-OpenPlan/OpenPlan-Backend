package com.openplan.backend.project.dto;

import com.openplan.backend.project.domain.Project;
import com.openplan.backend.task.repository.ProjectTaskStatsRow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 프로젝트 응답 (상세·목록 항목·쓰기 응답 공용 — 경계 계약 B-2로 이 필드 전체로 한정).
 * version 포함(편집 낙관락 입력). updated_at은 스키마에 없으므로 응답에도 없다.
 *
 * <p>{@code badges}·{@code taskStats}는 PROJ-01/04(이슈#17) — {@link ProjectTaskStatsRow}를 그대로
 * 옮겨 담는다. {@code badges.assignedCount}는 "배치됨"(미배치 아님) = IN_PROGRESS+COMPLETED 합.
 */
public record ProjectResponse(
        UUID projectId,
        String name,
        String description,
        LocalDate dueDate,
        String status,
        Integer priority,
        Instant closedAt,
        Badges badges,
        TaskStats taskStats,
        long version,
        Instant createdAt) {

    public static ProjectResponse from(Project p, ProjectTaskStatsRow stats) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getDueDate(),
                p.getStatus().name(),
                p.getPriority(),
                p.getClosedAt(),
                new Badges(stats.unassignedCount(), stats.inProgressCount() + stats.completedCount(),
                        stats.deadlineSoon()),
                new TaskStats(stats.total(), stats.unassignedCount(), stats.inProgressCount(),
                        stats.completedCount()),
                p.getVersion(),
                p.getCreatedAt());
    }

    /** 태스크 집계 전이면(아직 조회 전) 신규 프로젝트처럼 빈 집계로 — 생성 직후(PROJ-02)가 유일한 실사용처. */
    public static ProjectResponse from(Project p) {
        return from(p, ProjectTaskStatsRow.empty(p.getId()));
    }

    /** PROJ-01/04 FR-135~138 — 미배치·배치됨 개수 + 마감임박 강조. */
    public record Badges(long unassignedCount, long assignedCount, boolean deadlineSoon) {
    }

    /** PROJ-04 상세 전용 — 상태별 전체 분해. */
    public record TaskStats(long total, long unassigned, long inProgress, long completed) {
    }
}
