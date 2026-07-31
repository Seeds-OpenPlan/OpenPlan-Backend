package com.openplan.backend.rule.model;

import java.time.LocalDate;

/**
 * 태스크 사실 (V5·V6 판정용, 현재 미구현). dueDate·wbsStart·wbsEnd 는 null 허용.
 */
public record TaskFacts(LocalDate dueDate, LocalDate wbsStart, LocalDate wbsEnd,
                        int estimatedMinutes, int priority) {}
