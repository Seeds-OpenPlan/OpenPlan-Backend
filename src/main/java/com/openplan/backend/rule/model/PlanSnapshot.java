package com.openplan.backend.rule.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 검증 입력 스냅샷 — BE-2(ST-B2-09)가 조립 (rule-engine-contract §3.1).
 * C-1: 시각은 referenceTime 으로만 주입(엔진에 Clock 없음).
 */
public record PlanSnapshot(
    LocalDate weekStartDate,
    ZoneId zone,                             // 요일·자정 경계 판정용
    Instant referenceTime,
    List<BlockView> blocks,                  // 판정 대상 배치
    List<FixedWindow> activeFixedSchedules,  // 해당 주 유효 창만 (조립이 예외·INACTIVE 제외)
    List<AvailabilityWindow> availabilities, // 요일 7행
    Map<UUID, TaskFacts> taskFacts           // taskId → 사실
) {}
