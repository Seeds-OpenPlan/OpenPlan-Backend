package com.openplan.backend.structuring.domain;

/**
 * 경고 해소를 위해 사용자가 이동할 화면 (SS-04 / RB-PROJ-02). 정본 {@code openapi.yaml}의 enum 2값.
 *
 * <p><b>실행 가능한 것만 제안한다</b> — CLOSED 프로젝트는 태스크 쓰기가 전부 422(D-10)라 어떤 action도
 * 수행할 수 없으므로 경고 자체를 내지 않는다
 * ({@link com.openplan.backend.structuring.service.StructureWarningPolicy} 참고).
 */
public enum StructureWarningAction {

    /** 태스크 추가 — 분해가 부족할 때. */
    ADD_TASK,

    /** 태스크 편집 — 예상시간 입력·범위 조정처럼 기존 태스크를 손봐야 할 때. */
    EDIT_TASK
}
