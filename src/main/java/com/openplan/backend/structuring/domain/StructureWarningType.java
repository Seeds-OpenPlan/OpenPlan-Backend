package com.openplan.backend.structuring.domain;

/**
 * 구조 부족 경고 유형 (SS-04 / RB-PROJ-02).
 *
 * <p><b>선언 순서가 응답 순서다</b> — 정본 {@code openapi.yaml}의 enum 선언 순과 일치시킨다.
 * 동일 입력이 항상 문자 단위 동일 응답이어야 하므로(P1 결정성) 판정은 이 순서로 담는다.
 * 대시보드 위험 목록·검증 엔진 ruleId가 쓰는 것과 같은 관례다.
 */
public enum StructureWarningType {

    /** 태스크 총수가 기준 미만 — 아직 계획 가능한 수준으로 분해되지 않았다. */
    TOO_FEW_TASKS,

    /** 미완료 태스크 중 예상시간이 비어 있는 것이 있다 — 계획 산정이 그만큼 왜곡된다. */
    MISSING_ESTIMATES,

    /** 마감이 임박했는데 미완료 태스크가 남아 있다. */
    DEADLINE_PRESSURE
}
