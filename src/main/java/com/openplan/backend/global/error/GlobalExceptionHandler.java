package com.openplan.backend.global.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 예외 → 오류 봉투 단일 창구 (exceptions.md §3). 컨트롤러/서비스는 봉투를 직접 조립하지 않는다.
 *
 * <p>매핑 규칙(§3):
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} → E-COM-001 (+fields)</li>
 *   <li>{@link ObjectOptimisticLockingFailureException} → E-COM-006 (latest 재조회는 도메인 예외로 승격 시 동봉)</li>
 *   <li>{@link OpenPlanException} → 지정 ErrorCode 그대로</li>
 *   <li>미분류 Exception → E-COM-005</li>
 * </ul>
 * 로깅 레벨: 4xx=WARN(E-COM-001/009는 INFO), 5xx=ERROR+스택. 전부 traceId(MDC) 동반.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 사용자 노출 문구는 전부 여기서 해석한다 — 봉투 조립의 단일 지점(exceptions.md §1). */
    private final ErrorMessages errorMessages;

    public GlobalExceptionHandler(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    @ExceptionHandler(OpenPlanException.class)
    public ResponseEntity<ErrorResponse> handleOpenPlan(OpenPlanException ex, HttpServletRequest req) {
        ErrorCode code = ex.errorCode();
        String message = ex.overrideMessage() != null ? ex.overrideMessage() : errorMessages.resolve(code);
        logByStatus(code, message, req, ex);
        return build(code, message, ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.add(Map.of(
                    "field", fe.getField(),
                    "rule", fe.getCode() == null ? "invalid" : fe.getCode(),
                    "message", fe.getDefaultMessage() == null ? "" : fe.getDefaultMessage()));
        }
        ErrorCode code = ErrorCode.E_COM_001;
        log.info("[{}] validation failed uri={} fields={}", code.code(), req.getRequestURI(), fields.size());
        return build(code, errorMessages.resolve(code), Map.of("fields", fields));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                                              HttpServletRequest req) {
        ErrorCode code = ErrorCode.E_COM_006;
        log.warn("[{}] optimistic lock uri={}", code.code(), req.getRequestURI());
        // details.latest(최신본)는 도메인 서비스가 OpenPlanException으로 승격해 동봉하는 것이 원칙.
        // 여기 도달한 경우는 최신본 없이 충돌만 안내(SYS-05 재시도 유도).
        return build(code, errorMessages.resolve(code), null);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest req) {
        ErrorCode code = ErrorCode.E_COM_004;
        log.warn("[{}] not found uri={}", code.code(), req.getRequestURI());
        return build(code, errorMessages.resolve(code), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        ErrorCode code = ErrorCode.E_COM_005;
        log.error("[{}] unexpected uri={}", code.code(), req.getRequestURI(), ex);
        return build(code, errorMessages.resolve(code), null);
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, message, details));
    }

    private void logByStatus(ErrorCode code, String message, HttpServletRequest req, Exception ex) {
        int status = code.status().value();
        if (status >= 500) {
            log.error("[{}] {} uri={}", code.code(), message, req.getRequestURI(), ex);
        } else if (code == ErrorCode.E_COM_001 || code == ErrorCode.E_COM_009) {
            log.info("[{}] {} uri={}", code.code(), message, req.getRequestURI());
        } else {
            log.warn("[{}] {} uri={}", code.code(), message, req.getRequestURI());
        }
    }
}
