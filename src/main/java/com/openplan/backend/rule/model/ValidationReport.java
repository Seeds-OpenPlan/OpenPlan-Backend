package com.openplan.backend.rule.model;

import java.time.Instant;
import java.util.List;

/** 검증 결과. savable = 차단(BLOCK) 0건이면 true (PLAN-28). issues 는 정렬된 불변 리스트. */
public record ValidationReport(boolean savable, List<ValidationIssue> issues, Instant evaluatedAt) {}
