package com.openplan.backend.executionlog.service;

import com.openplan.backend.executionlog.domain.ExecutionResult;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 수행 이력 값 검증 (422 E-COM-009). DB 제약({@code ck_exec_range}·{@code ck_exec_actual}·
 * {@code ck_exec_result})과 이중 방어하며, 저장 전에 서버가 먼저 거른다.
 *
 * <p>필드 메시지는 {@code messages/errors.properties}의 {@code validation.{field}.{rule}} 키로 해석한다.
 * ⚠️ 응답의 {@code fields[].field}는 <b>요청 본문의 필드 이름과 같아야 한다</b> — 프론트가 그 값으로
 * 입력란에 오류를 붙인다(PR #24 리뷰 지적).
 */
@Component
public class ExecutionLogValidator {

    private static final int STEP_MINUTES = 5; // NFR-010

    private final ErrorMessages errorMessages;

    public ExecutionLogValidator(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    /** 시각 규칙: 둘 다 필수 → 시작&le;종료({@code ck_exec_range}). 같은 시각은 허용(0분 수행 아님 — 분은 별도 필드). */
    public void validateTimes(Instant startedAt, Instant endedAt) {
        if (startedAt == null) {
            throw invalid("startedAt", "required");
        }
        if (endedAt == null) {
            throw invalid("endedAt", "required");
        }
        if (endedAt.isBefore(startedAt)) {
            throw invalid("endedAt", "range");
        }
    }

    /** 실제 시간 규칙: 필수 → 5분 배수이면서 0보다 큼({@code ck_exec_actual} · 명세 multipleOf 5 · minimum 5). */
    public void validateActualMinutes(Integer actualMinutes) {
        if (actualMinutes == null) {
            throw invalid("actualMinutes", "required");
        }
        if (actualMinutes <= 0 || actualMinutes % STEP_MINUTES != 0) {
            throw invalid("actualMinutes", "step");
        }
    }

    /**
     * result 규칙: null/미정의 문자열 → 실패. String으로 받아 여기서 enum 매핑(파싱 단계 500 회피).
     * @return 매핑된 {@link ExecutionResult}.
     */
    public ExecutionResult resolveResult(String result) {
        if (result == null) {
            throw invalid("result", "required");
        }
        try {
            return ExecutionResult.valueOf(result.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("result", "invalid");
        }
    }

    /** 계획 블록 참조 규칙: 담겨 왔는데 내 것이 아니면 실패(존재 여부를 알리지 않기 위해 422로 통일). */
    public void rejectUnownedPlanBlock() {
        throw invalid("planBlockId", "invalid");
    }

    private OpenPlanException invalid(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
