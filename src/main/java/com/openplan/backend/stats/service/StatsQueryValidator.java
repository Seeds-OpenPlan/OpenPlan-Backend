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

    private OpenPlanException invalid(String field) {
        String message = errorMessages.resolve("validation." + field + ".invalid");
        return new OpenPlanException(
                ErrorCode.E_COM_009,
                Map.of("fields", List.of(Map.of("field", field, "rule", "invalid", "message", message))));
    }
}
