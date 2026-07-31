package com.openplan.backend.rule.model;

import java.time.DayOfWeek;
import java.util.UUID;

/**
 * 단건 판정 (OpenAPI ValidationIssue 스키마와 1:1).
 * 요일 단위 규칙(V3)에서는 planBlockId·taskId 가 null, weekday 가 채워진다.
 * reason 은 null 불가 (C-3).
 */
public record ValidationIssue(RuleId ruleId, Severity severity,
                              UUID planBlockId, UUID taskId, DayOfWeek weekday,
                              String reason) {}
