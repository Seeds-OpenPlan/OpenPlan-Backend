package com.openplan.backend.task.repository;

import java.util.UUID;

/**
 * 프로젝트 배지·태스크 집계 프로젝션 (PROJ-01/04). {@link TaskRepository#findStatsByProjectIds}가
 * 프로젝트별로 그룹화해 담는다. 태스크가 0건인 프로젝트는 쿼리에 행 자체가 없으므로 {@link #empty(UUID)}로
 * 호출자가 기본값(전부 0)을 메운다.
 */
public record ProjectTaskStatsRow(UUID projectId, long unassignedCount, long inProgressCount,
                                  long completedCount, long deadlineSoonCount) {

    public static ProjectTaskStatsRow empty(UUID projectId) {
        return new ProjectTaskStatsRow(projectId, 0L, 0L, 0L, 0L);
    }

    public long total() {
        return unassignedCount + inProgressCount + completedCount;
    }

    /** "마감 임박" = 공용 정의(DEADLINE_SOON_DAYS) 창 안의 미완료 태스크가 1건 이상. */
    public boolean deadlineSoon() {
        return deadlineSoonCount > 0;
    }
}
