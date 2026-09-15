package com.openplan.backend.rule.model;

import java.time.LocalDate;

/**
 * 태스크 사실 (V5·V6 판정용). dueDate·wbsStart·wbsEnd 는 null 허용 —
 * <b>null = 그 규칙의 판정 제외</b>다(마감일 미설정·WBS 미설정은 위반이 아니라 판정 대상이 아님).
 */
public record TaskFacts(LocalDate dueDate, LocalDate wbsStart, LocalDate wbsEnd,
                        int estimatedMinutes, int priority) {}
