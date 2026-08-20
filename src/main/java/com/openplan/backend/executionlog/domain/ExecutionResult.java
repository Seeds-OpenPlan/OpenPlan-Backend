package com.openplan.backend.executionlog.domain;

/**
 * 수행 결과 (PLAN-15) — V1 baseline {@code ck_exec_result} 3값과 1:1.
 *
 * <p>정본 openapi.yaml {@code ExecutionLog.result} enum 과 동일하다. 값 추가는 DB CHECK 제약과
 * 동시에 바꿔야 한다(계약 변경은 양쪽 동시 반영 — ADR-0013).
 */
public enum ExecutionResult {
    /** 계획대로 끝냄. */
    COMPLETED,
    /** 늦어짐. */
    DELAYED,
    /** 중단. */
    ABORTED
}
