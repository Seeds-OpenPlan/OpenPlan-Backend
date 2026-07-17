package com.openplan.backend.global.error;

import java.util.Map;

/**
 * 도메인 예외 — 컨트롤러/서비스는 봉투를 직접 조립하지 않고 이 예외를 throw 한다
 * (exceptions.md §3 "단일 창구"). 봉투 변환은 {@link GlobalExceptionHandler}가 전담.
 */
public class OpenPlanException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public OpenPlanException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public OpenPlanException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, details, null);
    }

    public OpenPlanException(ErrorCode errorCode, Map<String, Object> details, String overrideMessage) {
        super(overrideMessage != null ? overrideMessage : errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }
}
