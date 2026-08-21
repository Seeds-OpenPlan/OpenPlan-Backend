package com.openplan.backend.dashboard.domain;

/** DASH-03/04 위험 목록 유형 — openapi {@code riskIssues[].riskType} enum과 1:1. */
public enum RiskType {
    UNASSIGNED_TASKS, OUT_OF_WBS, FIXED_CONFLICT, DEADLINE_SOON
}
