package com.openplan.backend.project.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 프로젝트 도메인 값 검증 (422 E-COM-009). 생성/편집/재개가 <b>동일 메서드를 재사용</b>해
 * 검증 대칭을 구조적으로 보장한다(AC-06-2 비대칭 금지). 상태 판단은 하지 않는다(엔티티 소관).
 */
@Component
public class ProjectValidator {

    private static final int NAME_MAX = 100;

    /**
     * name 규칙: null(키 부재 포함)·공백만 → 실패, trim 후 1~100자.
     *
     * <p><b>C2 — 첫 줄 null 가드</b>: name은 이 도메인에서 "null도 실패해야 하는 유일한 필수 필드"다.
     * 무가드 {@code name.trim()}은 NPE → 500 E-COM-005로 새어 AC-02-3/06-2(422)를 위반한다.
     *
     * @return trim된 이름(영속용)
     */
    public String validateName(String name) {
        String trimmed = (name == null) ? "" : name.trim(); // ← C2 null 가드(첫 줄)
        if (trimmed.isEmpty()) {
            throw invalid("name", "required", "이름은 필수입니다.");
        }
        if (trimmed.length() > NAME_MAX) {
            throw invalid("name", "size", "이름은 100자 이하여야 합니다.");
        }
        return trimmed;
    }

    /**
     * dueDate 규칙(Q-J): null 허용(무기한), 값이면 today 이상. 과거 마감일은 생성 즉시 자동종료되는
     * 무의미한 생성이므로 거부한다. 당일은 허용(SSM §1 "당일까지 진행중").
     *
     * @param today 사용자 timezone 기준 오늘(UserClock 주입 — 결정성)
     */
    public void validateDueDate(LocalDate dueDate, LocalDate today) {
        if (dueDate != null && dueDate.isBefore(today)) {
            throw invalid("dueDate", "past", "마감일은 오늘 이후여야 합니다.");
        }
    }

    private OpenPlanException invalid(String field, String rule, String message) {
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
