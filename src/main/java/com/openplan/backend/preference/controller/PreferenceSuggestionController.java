package com.openplan.backend.preference.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.preference.dto.PreferenceSuggestionResponse;
import com.openplan.backend.preference.service.PreferenceSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 규칙 기반 기본값 제안 (SS-14 · RB-FIX-01).
 *
 * <p>{@code /users/me/preferences}(현재 설정)의 형제 경로다 — 자식이 아니다. 제안은 설정의 일부가
 * 아니라 <b>설정을 바꾸자는 별개의 읽기</b>이고, 적용은 사용자가 그 PUT 으로 직접 한다(C-2).
 */
@RestController
@RequestMapping("/users/me/preference-suggestions")
@Tag(name = "users", description = "규칙 기반 기본값 제안 (SS-14)")
public class PreferenceSuggestionController {

    private final PreferenceSuggestionService service;

    public PreferenceSuggestionController(PreferenceSuggestionService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "규칙 기반 기본값 제안 (SS-14)",
            description = "최근 4주 실행 기록의 중앙값·빈도로 계산한다. 이력이 모자라면 오류가 아니라 "
                    + "data=null 이다. 제안만 하며 적용은 PUT /users/me/preferences 로 사용자가 한다(C-2).")
    public ApiResponse<PreferenceSuggestionResponse> get(@CurrentUser UUID userId) {
        return ApiResponse.ok(service.suggest(userId));
    }
}
