package com.openplan.backend.stats.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
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
 * 수행 통계 API (ST-B2-16 — RB-STAT-01/03). {@code /stats/correction-proposals}는 이 컨트롤러에 없다
 * (SS-11 산출식 미정 — stats-dashboard-notes.md §1.2, openapi {@code not-implemented} 유지).
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
}
