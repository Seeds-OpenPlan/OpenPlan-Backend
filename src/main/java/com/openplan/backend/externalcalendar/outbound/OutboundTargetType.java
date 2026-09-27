package com.openplan.backend.externalcalendar.outbound;

/** 밖으로 내보내는 대상 (#69). 셋이 같은 아웃박스를 쓴다 — 전부 «시작·끝이 있는 일정» 으로 나간다. */
public enum OutboundTargetType {
    /** 개인 일정 — {@code schedules.schedule_id} */
    SCHEDULE,
    /** 고정 일정의 한 회차 — {@code fixed_schedule_occurrences} 의 PK (4단계) */
    FIXED_OCCURRENCE,
    /** 태스크 블록 — {@code plan_block_external_refs} 의 PK (5단계) */
    PLAN_BLOCK
}
