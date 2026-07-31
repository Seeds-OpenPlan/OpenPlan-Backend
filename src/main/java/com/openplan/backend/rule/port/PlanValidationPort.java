package com.openplan.backend.rule.port;

import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ValidationReport;

/**
 * SS-06 계획 적정성 점검 (USP-1 심장).
 * 동일 snapshot → 동일 report (골든 테스트 대상). NFR-029: validate ≤ 500ms.
 */
public interface PlanValidationPort {
    ValidationReport validate(PlanSnapshot snapshot);
}
