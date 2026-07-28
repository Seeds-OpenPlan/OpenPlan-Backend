package com.openplan.backend.task.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 태스크 도메인 값 검증 (422 E-COM-009). 생성/편집이 <b>동일 메서드를 재사용</b>해 검증 대칭을
 * 구조적으로 보장한다(AC-E-3 비대칭 금지). 상태 판단·참조 존재 판정은 하지 않는다.
 *
 * <p>필드 메시지는 인라인 하드코딩하지 않고 {@code messages/errors.properties}에서 {@code validation.{field}.{rule}}
 * 키로 해석한다({@link ErrorMessages}) — 사용자 노출 문구의 단일 원천·P4 감사 일원화.
 *
 * <p><b>dueDate 검증 메서드가 없는 것은 의도된 비대칭</b>(D-11): 태스크는 자동 종료가 없어 과거
 * 마감일을 허용한다. {@code ProjectValidator.validateDueDate}를 재사용/복붙하면 AC-C-6이 조용히
 * 깨진다(ADR-B2-03-005 구현 함정 경고 — 지명 테스트 [T3]로 회귀 방어).
 */
@Component
public class TaskValidator {

    private static final int TITLE_MAX = 200;

    private final ErrorMessages errorMessages;

    public TaskValidator(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    /**
     * title 규칙: null(키 부재 포함)·공백만 → 실패, trim 후 1~200자.
     *
     * <p><b>첫 줄 null 가드</b>(C2 승계): title은 "null도 422여야 하는 유일한 필수 필드"다.
     * 무가드 {@code title.trim()}은 NPE → 500으로 새어 AC-C-3(422)을 위반한다.
     *
     * @return trim된 제목(영속용)
     */
    public String validateTitle(String title) {
        String trimmed = (title == null) ? "" : title.trim(); // ← 첫 줄 null 가드(C2)
        if (trimmed.isEmpty()) {
            throw invalid("title", "required");
        }
        if (trimmed.length() > TITLE_MAX) {
            throw invalid("title", "size");
        }
        return trimmed;
    }

    /**
     * estimatedMinutes 규칙(D-6): null 허용(미입력=null 영속), 값이면 5의 배수이면서 0보다 커야 한다.
     * {@code ck_tasks_estimated} CHECK를 사전 검증해 위반이 500이 아니라 422로 라우팅되게 한다.
     */
    public void validateEstimatedMinutes(Integer estimatedMinutes) {
        if (estimatedMinutes == null) {
            return; // D-6 — null 통과(서버 기본값 미주입, FIX-11)
        }
        if (estimatedMinutes <= 0 || estimatedMinutes % 5 != 0) {
            throw invalid("estimatedMinutes", "step");
        }
    }

    /**
     * priority 규칙: null 허용(미지정), 값이면 1(높음)·2(보통)·3(낮음) 세 단계만 허용.
     * V1 스키마엔 CHECK가 없어 앱이 사전 검증한다 — 제품 우선순위 3단계 규칙(리뷰 반영 2026-07-28).
     */
    public void validatePriority(Integer priority) {
        if (priority == null) {
            return; // null 통과(미지정 허용)
        }
        if (priority < 1 || priority > 3) {
            throw invalid("priority", "range");
        }
    }

    /** field·rule로 카탈로그 키({@code validation.{field}.{rule}})를 유도해 문구를 해석한다. */
    private OpenPlanException invalid(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
