package com.openplan.backend.landing.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.landing.dto.LandingResponse;
import com.openplan.backend.landing.service.LandingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 랜딩 컨트롤러(ST-B1-14 · LANDING).
 *
 * <p>비인증 접근(AC② — 스텁 기간에도 통과). SecurityConfig의 {@code /api/v1/landing} 공개 경로로 가드 예외.
 * 경로는 접두 없이 매핑하고 프레임워크가 {@code /api/v1}을 부여한다.
 */
@RestController
@Tag(name = "landing", description = "랜딩 (비인증)")
public class LandingController {

    private final LandingService landingService;

    public LandingController(LandingService landingService) {
        this.landingService = landingService;
    }

    @GetMapping("/landing")
    @Operation(summary = "랜딩 콘텐츠 (LANDING) — 비인증")
    public ApiResponse<LandingResponse> getLanding() {
        return ApiResponse.ok(landingService.getLanding());
    }
}
