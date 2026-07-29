package com.openplan.backend.plan.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ValidationReport;
import com.openplan.backend.rule.port.PlanValidationPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 계획 검증 dry-run 접합 스텁 (ST-B2-09의 일부) — <b>임시 클래스</b>.
 *
 * <p>ST-B2-09의 핵심 책임인 <b>스냅샷 조립</b>(blocks + 해당 주 유효 고정일정만 + availabilities 7행
 * + taskFacts + referenceTime·zone)은 BE-2 소관이고, 주간계획·블록 도메인이 아직 없다.
 * 그래서 이 스텁은 조립부를 비운 채 <b>스냅샷을 요청 바디로 그대로 받아</b> 엔진에 넘긴다.
 * 저장소를 거치지 않으므로 어떤 영속도 발생하지 않는다(ST-B2-09 AC1 dry-run).
 *
 * <p><b>인계</b>: BE-2가 ST-B2-09를 구현할 때 {@code @RequestBody PlanSnapshot}을
 * 조립 결과로 바꾸면 {@link PlanValidationPort} 계약 변경 없이 그대로 본구현이 된다.
 * 그 시점에 이 클래스는 삭제한다. 같은 경로를 쓰므로 본구현이 들어오면 매핑이 충돌해
 * 삭제를 강제한다(의도된 안전장치).
 *
 * <p><b>주의</b>: 검증 위반은 오류가 아니다. 위반이 있어도 200 + {@code savable} 로 응답한다
 * (ST-B2-09 AC4 — 오류 봉투는 확정 라우트의 {@code E-PLAN-004} 하나뿐이고, 확정은 이 스텁 범위 밖).
 */
@RestController
@RequestMapping("/weekly-plans")
@Tag(name = "plan-validation", description = "계획 검증 (ST-B2-09 dry-run 스텁 — BE-2 본구현 시 대체)")
public class PlanValidationStubController {

    private final PlanValidationPort validator;

    public PlanValidationStubController(PlanValidationPort validator) {
        this.validator = validator;
    }

    /**
     * 스냅샷을 받아 판정만 돌려준다. {@code planId}는 본구현의 경로 계약을 미리 맞춰두기 위한 것으로,
     * 스텁 단계에서는 조회에 쓰이지 않는다(조립이 없으므로).
     */
    @PostMapping("/{planId}/validations")
    @Operation(summary = "계획 검증 dry-run (스텁) — 스냅샷을 바디로 받아 판정, 영속 없음")
    public ApiResponse<ValidationReport> dryRun(@PathVariable UUID planId,
                                                @RequestBody PlanSnapshot snapshot) {
        return ApiResponse.ok(validator.validate(snapshot));
    }
}
