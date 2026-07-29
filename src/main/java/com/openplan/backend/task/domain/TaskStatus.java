package com.openplan.backend.task.domain;

/**
 * 태스크 상태 (ST-B2-03 status-state-machine). V1 baseline {@code ck_tasks_status} CHECK와 1:1 —
 * 3값 외 추가 금지(추가 시 Flyway 마이그레이션 필요).
 *
 * <p><b>canTransitionTo 없음</b>(ADR-B2-03-001): 전이 합법성이 (from,to) 쌍만으로 인코딩되지 않고
 * 외부 사실(블록 존재 여부)에 의존한다 — 전이 규칙은 {@link Task}의 상태머신 메서드 4개가 소유한다.
 */
public enum TaskStatus {
    UNASSIGNED,
    IN_PROGRESS,
    COMPLETED
}
