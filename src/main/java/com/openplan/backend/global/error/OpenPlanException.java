package com.openplan.backend.global.error;

import java.util.Map;

/**
 * 도메인 예외 — 컨트롤러/서비스는 봉투를 직접 조립하지 않고 이 예외를 throw 한다
 * (exceptions.md §3 "단일 창구"). 봉투 변환은 {@link GlobalExceptionHandler}가 전담.
 */
public class OpenPlanException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;
    private final String overrideMessage;

    public OpenPlanException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public OpenPlanException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, details, null);
    }

    /**
     * @param overrideMessage 카탈로그 문구 대신 쓸 문구(대개 null). null이면
     *                        {@link GlobalExceptionHandler}가 {@link ErrorMessages}로 해석한다 —
     *                        여기서 해석하지 않는 이유는 예외 생성이 Spring 컨텍스트 밖에서도
     *                        일어나기 때문이다.
     */
    public OpenPlanException(ErrorCode errorCode, Map<String, Object> details, String overrideMessage) {
        // 예외 메시지(로그용)는 코드 — 사용자 노출 문구는 봉투 조립 시점에 카탈로그에서 해석한다.
        super(overrideMessage != null ? overrideMessage : errorCode.code());
        this.errorCode = errorCode;
        this.details = details;
        this.overrideMessage = overrideMessage;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** null이면 카탈로그 문구를 쓴다. */
    public String overrideMessage() {
        return overrideMessage;
    }

    public Map<String, Object> details() {
        return details;
    }
}
