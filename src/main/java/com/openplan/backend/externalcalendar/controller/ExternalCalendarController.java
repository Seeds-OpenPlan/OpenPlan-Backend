package com.openplan.backend.externalcalendar.controller;

import com.openplan.backend.externalcalendar.domain.ApplyStatus;
import com.openplan.backend.externalcalendar.dto.ApplyEventRequest;
import com.openplan.backend.externalcalendar.dto.ApplyEventResponse;
import com.openplan.backend.externalcalendar.dto.CreateConnectionRequest;
import com.openplan.backend.externalcalendar.dto.ExternalConnectionResponse;
import com.openplan.backend.externalcalendar.dto.ExternalEventResponse;
import com.openplan.backend.externalcalendar.dto.ProviderCalendarResponse;
import com.openplan.backend.externalcalendar.dto.SaveSelectionsRequest;
import com.openplan.backend.externalcalendar.dto.UpdateConnectionRequest;
import com.openplan.backend.externalcalendar.service.ExternalCalendarService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 외부 캘린더 연동 API (ONB-07/08/09 · FIX-13~17) — {@code /api/v1}은 WebConfig가 부여.
 *
 * <p>경로가 두 갈래(연동 리소스와 일정 리소스)라 클래스 레벨 prefix 를 두지 않고 메서드마다 전체 경로를 적는다.
 * 성공 봉투 {@link ApiResponse}, 오류는 OpenPlanException → GlobalExceptionHandler 단일 창구.
 */
@RestController
@Tag(name = "external-calendar", description = "외부 캘린더 연동 (ONB-07~09 · FIX-13~17)")
public class ExternalCalendarController {

    private final ExternalCalendarService externalCalendarService;

    public ExternalCalendarController(ExternalCalendarService externalCalendarService) {
        this.externalCalendarService = externalCalendarService;
    }

    @GetMapping("/external-calendar-authorization")
    @Operation(summary = "캘린더 연동 인가 시작 (ONB-07 · FIX-14) — 302 제공자",
            description = "캘린더 읽기 scope · access_type=offline · prompt=consent · 서명 state 를 서버가 실어 보낸다. "
                    + "access_type=offline 이 빠지면 refresh 토큰이 발급되지 않아 약 한 시간 뒤 연동이 죽는다.")
    public ResponseEntity<Void> startAuthorization(
            @RequestParam String provider,
            @RequestParam String redirectUri) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(externalCalendarService.authorizationUrl(provider, redirectUri)))
                .build();
    }

    @GetMapping("/external-calendar-connections")
    @Operation(summary = "연동 목록·상태 (FIX-13)",
            description = "내 외부 캘린더 연동을 연결 시각 순으로 반환. 각 항목에 선택된 캘린더 목록이 함께 실린다.")
    public ApiResponse<List<ExternalConnectionResponse>> list(@CurrentUser UUID userId) {
        return ApiResponse.ok(externalCalendarService.list(userId));
    }

    @PostMapping("/external-calendar-connections")
    @Operation(summary = "연동 추가 (ONB-07 · FIX-14)",
            description = "제공자 인가 코드를 토큰으로 교환해 연결한다. 같은 계정 재연결 → 409 E-EXT-004, "
                    + "제공자 오류·타임아웃 → 502 E-EXT-001(서버 재시도 없음).")
    public ResponseEntity<ApiResponse<ExternalConnectionResponse>> create(
            @CurrentUser UUID userId,
            @Valid @RequestBody CreateConnectionRequest request) {
        ExternalConnectionResponse created = externalCalendarService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PatchMapping("/external-calendar-connections/{connectionId}")
    @Operation(summary = "연동 활성/비활성 (FIX-16)",
            description = "CONNECTED·DISABLED 로 전환. 비활성화하면 이 연동에서 온 고정 일정이 같은 트랜잭션에서 "
                    + "INACTIVE 가 되어 계획 검증 스냅샷에서 빠진다. 재활성 시 되돌아온다.")
    public ApiResponse<ExternalConnectionResponse> updateStatus(
            @CurrentUser UUID userId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateConnectionRequest request) {
        return ApiResponse.ok(externalCalendarService.updateStatus(userId, connectionId, request));
    }

    @DeleteMapping("/external-calendar-connections/{connectionId}")
    @Operation(summary = "연동 해제 (FIX-17)",
            description = "연결을 지운다. 이 연동에서 온 고정 일정·캘린더 선택·후보 일정이 함께 사라지며 "
                    + "이후 주간 계획에 반영되지 않는다.")
    public ResponseEntity<Void> delete(@CurrentUser UUID userId, @PathVariable UUID connectionId) {
        externalCalendarService.delete(userId, connectionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/external-calendar-connections/{connectionId}/calendars")
    @Operation(summary = "제공자 캘린더 목록 (ONB-08)",
            description = "제공자에서 캘린더 목록을 읽어 온다. 이미 가져오기로 선택한 캘린더는 selected=true.")
    public ApiResponse<List<ProviderCalendarResponse>> listCalendars(
            @CurrentUser UUID userId,
            @PathVariable UUID connectionId) {
        return ApiResponse.ok(externalCalendarService.listCalendars(userId, connectionId));
    }

    @PutMapping("/external-calendar-connections/{connectionId}/calendar-selections")
    @Operation(summary = "가져올 캘린더 선택 저장 (ONB-08 · FIX-15)",
            description = "전체 교체다 — 목록에서 빠진 캘린더는 선택 해제가 아니라 삭제된다. "
                    + "빈 배열은 '아무것도 가져오지 않음'을 뜻한다.")
    public ApiResponse<Void> saveSelections(
            @CurrentUser UUID userId,
            @PathVariable UUID connectionId,
            @Valid @RequestBody SaveSelectionsRequest request) {
        externalCalendarService.saveSelections(userId, connectionId, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/external-calendar-connections/{connectionId}/events")
    @Operation(summary = "가져온 일정 후보 목록 (ONB-08/09)",
            description = "선택 캘린더 기준으로 동기화를 수행한 뒤 반환한다(별도 배치 없음). "
                    + "applyStatus 로 CANDIDATE·APPLIED·EXCLUDED 필터. 비활성 연동은 동기화하지 않고 기록만 반환한다.")
    public ApiResponse<List<ExternalEventResponse>> listEvents(
            @CurrentUser UUID userId,
            @PathVariable UUID connectionId,
            @RequestParam(required = false) ApplyStatus applyStatus) {
        return ApiResponse.ok(externalCalendarService.listEvents(userId, connectionId, applyStatus));
    }

    @PostMapping("/external-calendar-events/{eventId}/application")
    @Operation(summary = "반영 방식 적용 (ONB-09)",
            description = "AS_IS·EDITED 는 고정 일정(source=EXTERNAL)을 만들고 EXCLUDE 는 제외로 기록한다. "
                    + "생성된 고정 일정은 이후 주간 계획의 배치 불가 시간이 된다.")
    public ResponseEntity<ApiResponse<ApplyEventResponse>> apply(
            @CurrentUser UUID userId,
            @PathVariable UUID eventId,
            @Valid @RequestBody ApplyEventRequest request) {
        ApplyEventResponse applied = externalCalendarService.apply(userId, eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(applied));
    }
}
