package com.openplan.backend.externalcalendar.domain;

/**
 * ONB-09 반영 방식 — openapi {@code applyExternalEvent} 의 {@code mode} 와 같은 값.
 *
 * <p>baseline CHECK 는 {@code MODIFIED}/{@code EXCLUDED} 였고 계약은 {@code EDITED}/{@code EXCLUDE} 였다.
 * 3값 중 2값이 어긋나 요청값을 그대로 저장하면 CHECK 위반 500 이 나던 것을
 * {@code V202608192010} 이 계약 쪽으로 맞췄다.
 */
public enum ApplyMode {

    /** 제공자 일정 값 그대로 고정 일정 생성. */
    AS_IS,

    /** 요청 body 의 {@code edited} 값으로 고정 일정 생성. */
    EDITED,

    /** 고정 일정을 만들지 않고 제외로 기록 — 이후 동기화에서 되살아나지 않게 한다. */
    EXCLUDE
}
