package com.openplan.backend.rule.model;

import java.time.Instant;
import java.util.UUID;

/** 판정 대상 배치 블록 (현재 초안 또는 가상 배치 — dry-run은 호출자 소관, C-4). */
public record BlockView(UUID blockId, BlockType type, UUID taskId, UUID scheduleId,
                        Instant startAt, Instant endAt) {}
