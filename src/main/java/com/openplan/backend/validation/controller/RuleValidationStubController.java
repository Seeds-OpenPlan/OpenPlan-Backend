package com.openplan.backend.validation.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.rule.model.PlanSnapshot;
import com.openplan.backend.rule.model.ValidationReport;
import com.openplan.backend.rule.port.PlanValidationPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 규칙 엔진 확인용 dry-run 표면 — <b>임시 클래스. ST-B2-09 머지 시 삭제한다.</b>
 *
 * <p>규칙 엔진은 완성돼 있었으나 운영 코드에서 호출하는 경로가 없어 동작을 확인할 방법이 없었다.
 * 이 컨트롤러는 그 확인 경로만 뚫는다. 스냅샷을 받아 엔진에 넘기고 판정을 돌려줄 뿐,
 * 저장소를 거치지 않으므로 어떤 영속도 발생하지 않는다.
 *
 * <p><b>ST-B2-09가 아니다.</b> 검증 실행·계획 확정 라우트({@code POST /weekly-plans/{id}/validations},
 * {@code POST /weekly-plans/{id}/confirmation})와 그 핵심 책임인 <b>스냅샷 조립</b>은 BE-2 소관이고
 * 이 클래스는 거기에 관여하지 않는다. 경로·패키지도 주간계획 도메인과 겹치지 않게 분리했다.
 * 그래서 {@code planId}를 받지 않는다 — 조립이 없으니 조회할 대상도 없다.
 *
 * <p>인계용 참고: 소비자가 {@link PlanValidationPort}를 어떻게 부르면 되는지의 실행 가능한 예시다.
 * ST-B2-09는 조립 결과를 같은 방식으로 {@code validate()}에 넘기면 된다.
 *
 * <p><b>주의</b>: 검증 위반은 오류가 아니다. 위반이 있어도 200 + {@code savable}로 응답한다.
 * 오류 봉투({@code E-PLAN-004})는 확정 라우트에서만 쓰며 이 스텁 범위 밖이다.
 */
@RestController
@RequestMapping("/validations")
@Tag(name = "rule-validation-stub",
     description = "규칙 엔진 dry-run 확인용 임시 표면 — ST-B2-09 머지 시 삭제")
public class RuleValidationStubController {

    private final PlanValidationPort validator;

    public RuleValidationStubController(PlanValidationPort validator) {
        this.validator = validator;
    }

    @PostMapping("/dry-run")
    @Operation(summary = "규칙 엔진 dry-run (임시) — 스냅샷을 받아 판정만 반환, 영속 없음")
    public ApiResponse<ValidationReport> dryRun(@RequestBody PlanSnapshot snapshot) {
        return ApiResponse.ok(validator.validate(snapshot));
    }
}
