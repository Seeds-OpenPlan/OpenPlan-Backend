package com.openplan.backend.schedule.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 일정 도메인 값 검증 (422 E-COM-009). 생성(PLAN-08)·편집(PLAN-17)이 <b>동일 메서드를 재사용</b>한다.
 * 필드 메시지는 {@code messages/errors.properties}에서 {@code validation.{field}.{rule}} 키로 해석한다(단일 원천).
 *
 * <p>규칙은 태스크와 동일(제목 1~200·예상시간 5배수&gt;0·우선순위 1~3). 도메인 공통 규칙의 완전 통합은 후속 과제.
 */
@Component
public class ScheduleValidator {

    private static final int TITLE_MAX = 200;

    private final ErrorMessages errorMessages;

    public ScheduleValidator(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    /** title 규칙: null·공백만 → 실패, trim 후 1~200자. @return trim된 제목. */
    public String validateTitle(String title) {
        String trimmed = (title == null) ? "" : title.trim(); // 첫 줄 null 가드
        if (trimmed.isEmpty()) {
            throw invalid("title", "required");
        }
        if (trimmed.length() > TITLE_MAX) {
            throw invalid("title", "size");
        }
        return trimmed;
    }

    /** estimatedMinutes 규칙: null 허용, 값이면 5의 배수이면서 0보다 커야 한다(ck_schedules_estimated 사전 검증). */
    public void validateEstimatedMinutes(Integer estimatedMinutes) {
        if (estimatedMinutes == null) {
            return;
        }
        if (estimatedMinutes <= 0 || estimatedMinutes % 5 != 0) {
            throw invalid("estimatedMinutes", "step");
        }
    }

    /** priority 규칙: null 허용, 값이면 1(높음)·2(보통)·3(낮음). */
    public void validatePriority(Integer priority) {
        if (priority == null) {
            return;
        }
        if (priority < 1 || priority > 3) {
            throw invalid("priority", "range");
        }
    }

    private OpenPlanException invalid(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
