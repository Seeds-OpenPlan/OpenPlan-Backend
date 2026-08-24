package com.openplan.backend.settings.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.settings.dto.SettingsHomeResponse;
import com.openplan.backend.settings.service.SettingsHomeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 설정 홈 (D-12 레버 5 · FIX-01 · FIX-10 · FIX-13 · NOTI-01).
 *
 * <p>읽기 전용 조립이다. 저장은 각 조각의 원래 엔드포인트로 한다 — 여기에 쓰기를 두면
 * 부분 저장이 생기고, 무엇이 저장됐는지 응답만 보고는 알 수 없게 된다.
 */
@RestController
@RequestMapping("/settings")
@Tag(name = "settings", description = "설정 홈 조립 (D-12 레버 5)")
public class SettingsController {

    private final SettingsHomeService service;

    public SettingsController(SettingsHomeService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "설정 홈 조립 응답 (D-12 레버 5)",
            description = "가용시간·기본설정·외부연동·알림설정을 한 번에 읽는다. 저장은 각 조각의 "
                    + "원래 엔드포인트로 한다(이 경로는 읽기 전용).")
    public ApiResponse<SettingsHomeResponse> get(@CurrentUser UUID userId) {
        return ApiResponse.ok(service.get(userId));
    }
}
