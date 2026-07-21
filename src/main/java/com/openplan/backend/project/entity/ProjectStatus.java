package com.openplan.backend.project.entity;

/**
 * 프로젝트 상태 (ST-B2-01 status-state-machine).
 *
 * <p>V1 baseline {@code ck_projects_status} CHECK와 1:1 — 3값 외 추가 금지(추가 시 Flyway 마이그레이션 필요).
 * 전이 규칙은 {@link #canTransitionTo}에 인코딩한다(SSM 전이표 T1~T6).
 */
public enum ProjectStatus {
    IN_PROGRESS,
    PAUSED,
    CLOSED;

    /**
     * 이 상태에서 {@code target}으로의 <b>수동</b> 전이가 허용되는가.
     *
     * <p>불허는 CLOSED → PAUSED (T6) 단 1건이다(SSM §1). 동일 상태(no-op)는 이 메서드에
     * 도달하기 전에 서비스가 단락하므로 여기서 판단하지 않는다(계약: 호출 순서 — code-structure §3).
     */
    public boolean canTransitionTo(ProjectStatus target) {
        return !(this == CLOSED && target == PAUSED);
    }
}
