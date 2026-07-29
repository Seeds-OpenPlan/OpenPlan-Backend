package com.openplan.backend.category.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 카테고리 도메인 값 검증 (422 E-COM-009). 필드 메시지는 {@code messages/errors.properties}에서 해석한다
 * (사용자 노출 문구 단일 원천). 상태·참조 판정은 하지 않는다.
 */
@Component
public class TaskCategoryValidator {

    private static final int NAME_MAX = 50;

    private final ErrorMessages errorMessages;

    public TaskCategoryValidator(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    /**
     * name 규칙: null(키 부재 포함)·공백만 → 실패, trim 후 1~50자(VARCHAR(50) 정합).
     *
     * <p>첫 줄 null 가드: 무가드 {@code name.trim()}은 NPE → 500으로 새어 422 규칙을 위반한다.
     *
     * @return trim된 이름(영속·중복 판정용)
     */
    public String validateName(String name) {
        String trimmed = (name == null) ? "" : name.trim(); // ← null 가드(첫 줄)
        if (trimmed.isEmpty()) {
            throw invalid("name", "required", "validation.name.required");
        }
        if (trimmed.length() > NAME_MAX) {
            throw invalid("name", "size", "validation.taskCategoryName.size");
        }
        return trimmed;
    }

    private OpenPlanException invalid(String field, String rule, String messageKey) {
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of(
                        "field", field, "rule", rule, "message", errorMessages.resolve(messageKey)))));
    }
}
