package com.openplan.backend.externalcalendar.domain;

/**
 * 후보 일정의 반영 상태 — baseline {@code external_calendar_events.apply_status} (ck_ext_event_apply_status).
 * openapi {@code ExternalEvent.applyStatus} 및 목록 쿼리 파라미터와 값이 일치한다.
 */
public enum ApplyStatus {

    /** 동기화로 가져왔고 아직 사용자가 판단하지 않은 상태. */
    CANDIDATE,

    /** AS_IS·EDITED 로 고정 일정이 생성된 상태. */
    APPLIED,

    /** 사용자가 제외한 상태. */
    EXCLUDED
}
