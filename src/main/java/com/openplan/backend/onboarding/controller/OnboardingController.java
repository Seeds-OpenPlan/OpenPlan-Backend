package com.openplan.backend.onboarding.controller;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.onboarding.domain.OnboardingStep;
import com.openplan.backend.onboarding.dto.OnboardingContentItem;
import com.openplan.backend.onboarding.dto.OnboardingProgressResponse;
import com.openplan.backend.onboarding.dto.UpdateOnboardingProgressRequest;
import com.openplan.backend.onboarding.service.OnboardingContentService;
import com.openplan.backend.onboarding.service.OnboardingProgressService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 온보딩 컨트롤러(ST-B1-08 · ONB-01/03/05 · TUT-08/09).
 *
 * <p>경로는 접두 없이 매핑하고 프레임워크가 {@code /api/v1}을 부여한다. 진행 상태는 {@code @CurrentUser}로
 * 스코핑한다. 콘텐츠는 비개인 공개 문구지만 온보딩 흐름 내부라 인증 경로에 둔다(landing만 비인증).
 */
@RestController
@Tag(name = "onboarding", description = "온보딩 진행·콘텐츠 (ONB-01/03/05 · TUT-08/09)")
public class OnboardingController {

    private final OnboardingProgressService progressService;
    private final OnboardingContentService contentService;

    public OnboardingController(OnboardingProgressService progressService,
                               OnboardingContentService contentService) {
        this.progressService = progressService;
        this.contentService = contentService;
    }

    @GetMapping("/users/me/onboarding-progress")
    @Operation(summary = "온보딩·튜토리얼 진행 상태 조회 (ONB-01 · TUT-08)")
    public ApiResponse<OnboardingProgressResponse> getProgress(@CurrentUser UUID userId) {
        return ApiResponse.ok(progressService.getMyProgress(userId));
    }

    @PatchMapping("/users/me/onboarding-progress")
    @Operation(summary = "단계 완료/건너뛰기 · 튜토리얼 재실행 리셋 (TUT-08/09) — 부분 수정")
    public ApiResponse<OnboardingProgressResponse> updateProgress(@CurrentUser UUID userId,
                                                                 @RequestBody UpdateOnboardingProgressRequest request) {
        return ApiResponse.ok(progressService.updateProgress(userId, request));
    }

    @GetMapping("/onboarding/contents")
    @Operation(summary = "소개·안내 콘텐츠 (ONB-01/03/05) — step별 서버 시드 문구")
    public ApiResponse<List<OnboardingContentItem>> getContents(@RequestParam(value = "step", required = false) String step) {
        return ApiResponse.ok(contentService.getContents(parseStep(step)));
    }

    /**
     * step 쿼리값을 enum으로 변환. 누락·오탈자는 400 E-COM-001로 안내한다 — enum 파라미터를 직접 받으면
     * 변환 실패가 500이 되므로(전역 핸들러 미매핑), String으로 받아 여기서 검증한다.
     */
    private OnboardingStep parseStep(String step) {
        if (step == null || step.isBlank()) {
            throw new OpenPlanException(ErrorCode.E_COM_001, Map.of("field", "step", "rule", "required"));
        }
        try {
            return OnboardingStep.valueOf(step);
        } catch (IllegalArgumentException e) {
            throw new OpenPlanException(ErrorCode.E_COM_001, Map.of("field", "step", "rule", "enum"));
        }
    }
}
