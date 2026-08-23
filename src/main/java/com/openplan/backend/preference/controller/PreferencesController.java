package com.openplan.backend.preference.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.preference.dto.PreferencesRequest;
import com.openplan.backend.preference.dto.PreferencesResponse;
import com.openplan.backend.preference.service.PreferencesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 사용자 기본 설정 API (FIX-10·11·12) — {@code /users/me/preferences}.
 * 예상시간 기본값 · 재계획 전략 기본값 · <b>주간 가용 시간 목표</b>.
 */
@RestController
@RequestMapping("/users/me/preferences")
@Tag(name = "user", description = "사용자 기본 설정 (FIX-10~12)")
public class PreferencesController {

    private final PreferencesService service;

    public PreferencesController(PreferencesService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "사용자 기본 설정 조회 (FIX-10)",
            description = "설정한 적이 없으면 값이 전부 null 인 응답을 준다(404 아님) — '안 정함'이 정상 상태다.")
    public ApiResponse<PreferencesResponse> get(@CurrentUser UUID userId) {
        return ApiResponse.ok(service.get(userId));
    }

    @PutMapping
    @Operation(summary = "기본 설정 저장 (FIX-11·12)",
            description = "PUT 이라 전체 교체다 — 담겨 오지 않은 값은 지워진다. "
                    + "예상시간·주간 가용 시간은 5분 단위 양수, 전략은 4값 중 하나. 위반 → 422 E-COM-009.")
    public ApiResponse<PreferencesResponse> save(@CurrentUser UUID userId,
                                                 @RequestBody PreferencesRequest request) {
        return ApiResponse.ok(service.save(userId, request));
    }
}
