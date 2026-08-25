package com.openplan.backend.dashboard.controller;

import com.openplan.backend.dashboard.dto.DashboardQuery;
import com.openplan.backend.dashboard.dto.DashboardResponse;
import com.openplan.backend.dashboard.service.DashboardService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 대시보드 조립 API (ST-B2-15 — DASH-01~07 · RB-DASH-01/02, D-12 레버 5 단일 GET). */
@RestController
@RequestMapping("/dashboard")
@Tag(name = "dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "대시보드 조립 응답 — 단일 GET (D-12 레버 5, NFR-011 2초)",
            description = "진입 시 PROJ-08 지연 평가를 별도 tx로 트리거한다(ST-B2-01-AC2 재사용).")
    public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard(
            @CurrentUser UUID userId, @Valid @ModelAttribute DashboardQuery query) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getDashboard(userId, query)));
    }
}
