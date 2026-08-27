package com.openplan.backend.stats.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.ErrorMessages;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.stats.domain.DeviationGroupBy;
import com.openplan.backend.stats.domain.StatsPeriod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * period/groupBy 열거값 검증 (422 E-COM-009). 필수 여부(null/blank)는 각 Query DTO의
 * {@code @NotBlank}(→ 400 E-COM-001, {@code TaskListQuery} 선례)가 이미 걸렀으므로 여기서는
 * "값은 있는데 정의된 열거값이 아닌" 경우만 다룬다 — enum 바인딩을 컨트롤러 단에서 직접 받지 않는 이유와 동일
 * (미정의값이 파싱 단계에서 500으로 새는 것을 막기 위해 String으로 받아 서비스에서 매핑, {@code TaskListQuery.status} 선례).
 */
@Component
public class StatsQueryValidator {

    private final ErrorMessages errorMessages;

    public StatsQueryValidator(ErrorMessages errorMessages) {
        this.errorMessages = errorMessages;
    }

    public StatsPeriod resolvePeriod(String period) {
        try {
            return StatsPeriod.valueOf(period.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("period");
        }
    }

    public DeviationGroupBy resolveGroupBy(String groupBy) {
        try {
            return DeviationGroupBy.valueOf(groupBy.trim());
        } catch (IllegalArgumentException e) {
            throw invalid("groupBy");
        }
    }

    /**
     * 보정 제안(SS-11)의 {@code estimatedMinutes} 값 규칙 — 태스크 생성·편집과 동일식·동일 카탈로그 키.
     *
     * <p>null은 통과시킨다 — 정본상 이 쿼리는 선택이고, 미제공은 오류가 아니라 "제안 불가(data 생략)"라는
     * 의미 있는 입력이다({@code CorrectionProposalQuery} 참고).
     *
     * <p>규칙 자체는 {@code TaskValidator}와 같지만 그 클래스를 주입하지 않는다 — task 도메인 서비스를
     * stats 유스케이스에 끌어오면 도메인 경계를 넘는다. 카탈로그 키
     * ({@code validation.estimatedMinutes.step})를 공유해 사용자에게 나가는 문구는 한 원천을 유지한다.
     */
    public void validateEstimatedMinutes(Integer estimatedMinutes) {
        if (estimatedMinutes == null) {
            return;
        }
        if (estimatedMinutes <= 0 || estimatedMinutes % 5 != 0) {
            throw invalid("estimatedMinutes", "step");
        }
    }

    /** 열거값 위반 전용(rule="invalid" 고정) — 기존 호출부 보존을 위해 시그니처를 그대로 둔다. */
    private OpenPlanException invalid(String field) {
        return invalid(field, "invalid");
    }

    /**
     * field·rule을 함께 받는 형태. 1항 헬퍼는 rule과 카탈로그 키를 {@code "invalid"}로 <b>하드코딩</b>해서
     * {@code rule=step} 같은 다른 규칙을 표현할 수 없다 — {@code TaskValidator}가 쓰는 2항 형태를 승계한다.
     */
    private OpenPlanException invalid(String field, String rule) {
        String message = errorMessages.resolve("validation." + field + "." + rule);
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", rule, "message", message))));
    }
}
