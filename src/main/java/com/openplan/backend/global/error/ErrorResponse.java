package com.openplan.backend.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 오류 봉투 (ADR-0003 / exceptions.md §1 — 단일 양식).
 *
 * <pre>{ "error": { "code": "E-...", "message": "...", "details": { } } }</pre>
 *
 * traceId는 봉투에 넣지 않는다 — 응답 헤더 {@code X-Trace-Id}로만 노출(logging/TraceIdFilter).
 * 성공 응답은 {@link com.openplan.backend.global.response.ApiResponse} 사용.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(Body error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String code, String message, Map<String, Object> details) {
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorResponse(new Body(code.code(), message, details));
    }
}
