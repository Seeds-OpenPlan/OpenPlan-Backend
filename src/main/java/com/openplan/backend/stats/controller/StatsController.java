package com.openplan.backend.stats.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.stats.dto.CorrectionProposalQuery;
import com.openplan.backend.stats.dto.CorrectionProposalResponse;
import com.openplan.backend.stats.dto.DeviationReportResponse;
import com.openplan.backend.stats.dto.DeviationsQuery;
import com.openplan.backend.stats.dto.StatsSummaryQuery;
import com.openplan.backend.stats.dto.StatsSummaryResponse;
import com.openplan.backend.stats.dto.TimePatternReportResponse;
import com.openplan.backend.stats.dto.TimePatternsQuery;
import com.openplan.backend.stats.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 수행 통계 API (ST-B2-16 — RB-STAT-01/02/03 · SS-10/11/12). 보정 제안(SS-11)은 산출식 파라미터가
 * 정본에 없어 오래 보류돼 있었고, W3 게이트에서 확정한 뒤 편입했다.
 */
@RestController
@RequestMapping("/stats")
@Tag(name = "stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summaries")
    @Operation(summary = "주간/월간 요약 (SC-06 유지 4종)",
            description = "이력 0건은 오류가 아니라 data.empty=true 빈 상태로 응답한다(RB-STAT-01 GWT).")
    public ResponseEntity<ApiResponse<StatsSummaryResponse>> summaries(
            @CurrentUser UUID userId, @Valid @ModelAttribute StatsSummaryQuery query) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.summaries(userId, query)));
    }

    @GetMapping("/deviations")
    @Operation(summary = "편차 분석 (SS-10 — 프로젝트/카테고리 그룹)",
            description = "카테고리 미지정 태스크는 groupId=null, groupName='없음' 그룹으로 노출된다.")
    public ResponseEntity<ApiResponse<DeviationReportResponse>> deviations(
            @CurrentUser UUID userId, @Valid @ModelAttribute DeviationsQuery query) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.deviations(userId, query)));
    }

    @GetMapping("/time-patterns")
    @Operation(summary = "구간별 완료율 (SS-12 — DAWN/MORNING/AFTERNOON/NIGHT 고정 경계)")
    public ResponseEntity<ApiResponse<TimePatternReportResponse>> timePatterns(
            @CurrentUser UUID userId, @Valid @ModelAttribute TimePatternsQuery query) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.timePatterns(userId, query)));
    }

    @GetMapping("/correction-proposals")
    @Operation(summary = "예상 시간 보정 제안 (SS-11 / RB-STAT-02 — 제안만, 자동 적용 없음)",
            description = "입력 중인 예상값을 같은 스코프의 과거 편차율만큼 조정해 제안한다(읽기 전용). "
                    + "식: proposed = max(5, round5(estimatedMinutes × (100 + r) / 100)), r = 편차율 정수 반올림. "
                    + "편차율은 /stats/deviations와 같은 산법이고 집계 창은 전체 이력이라 시계에 의존하지 않는다. "
                    + "스코프 우선순위 categoryId > projectId > 전체이며 묵시 폴백은 없다 — 지정한 스코프의 "
                    + "이력이 부족하면 다른 스코프로 내려가지 않고 제안을 생략한다. "
                    + "제안 불가 3사유(표본 3건 미만 · 예상시간 합 0 · estimatedMinutes 미제공)는 모두 data 생략(200). "
                    + "상한·감쇠는 두지 않아 편차율이 크면 제안값도 그만큼 커진다. "
                    + "참조 ID 부재·타인 → 404, estimatedMinutes 5분 단위 위반 → 422.")
    public ResponseEntity<ApiResponse<CorrectionProposalResponse>> correctionProposals(
            @CurrentUser UUID userId, @Valid @ModelAttribute CorrectionProposalQuery query) {
        return ResponseEntity.ok(ApiResponse.ok(statsService.correctionProposal(userId, query)));
    }
}
