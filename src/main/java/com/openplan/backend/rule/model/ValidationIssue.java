package com.openplan.backend.rule.model;

import java.time.DayOfWeek;
import java.util.UUID;

/**
 * 단건 판정 (OpenAPI ValidationIssue 스키마와 1:1).
 *
 * <p>각 규칙은 <b>자기 판정 축(locus) 필드만</b> 채운다 (계약 §3.3, ADR-0013):
 * <ul>
 *   <li>요일 단위 규칙(V3): {@code weekday} 만 — {@code planBlockId}·{@code counterpartId}·{@code taskId} 는 null</li>
 *   <li>쌍 단위 규칙(V1·V2): {@code planBlockId}(대표) + {@code counterpartId}(상대) —
 *       {@code taskId}·{@code weekday} 는 null. 요일 정보는 {@code reason} 이 담는다</li>
 * </ul>
 * 소비처가 정의되지 않은 필드를 파생 편의값으로 채우지 않는다(값 창작 금지의 필드 적용).
 *
 * <p>{@code counterpartId} 는 쌍 규칙의 상대 엔티티 — V1: 상대 블록(UUID 큰 쪽), V2: fixedScheduleId.
 * 그 외 규칙은 null. {@code (planBlockId, counterpartId)} 조합이 쌍을 유일 식별한다.
 *
 * <p>{@code reason} 은 null 불가 (C-3).
 */
public record ValidationIssue(RuleId ruleId, Severity severity,
                              UUID planBlockId, UUID counterpartId,
                              UUID taskId, DayOfWeek weekday,
                              String reason) {}
