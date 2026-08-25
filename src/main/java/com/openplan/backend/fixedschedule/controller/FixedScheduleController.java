package com.openplan.backend.fixedschedule.controller;

import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import com.openplan.backend.fixedschedule.dto.ConflictPreviewRequest;
import com.openplan.backend.fixedschedule.dto.FixedScheduleCreateRequest;
import com.openplan.backend.fixedschedule.dto.FixedScheduleResponse;
import com.openplan.backend.fixedschedule.dto.FixedScheduleUpdateRequest;
import com.openplan.backend.fixedschedule.dto.WeekConflictResponse;
import com.openplan.backend.fixedschedule.dto.WeekExceptionCreateRequest;
import com.openplan.backend.fixedschedule.dto.WeekExceptionResponse;
import com.openplan.backend.fixedschedule.service.FixedScheduleConflictPreviewService;
import com.openplan.backend.fixedschedule.service.FixedScheduleService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 고정 일정 API (FIX-04·05) — {@code /fixed-schedules}. {@code /api/v1}은 WebConfig가 부여.
 * 성공 봉투 {@link ApiResponse}, 오류는 OpenPlanException → GlobalExceptionHandler 단일 창구.
 * (편집·삭제·주차예외·충돌 미리보기는 후속 슬라이스)
 */
@RestController
@RequestMapping("/fixed-schedules")
@Tag(name = "fixed-schedule", description = "고정 일정 CRUD (FIX-04~09)")
public class FixedScheduleController {

    private final FixedScheduleService fixedScheduleService;
    private final FixedScheduleConflictPreviewService conflictPreviewService;

    public FixedScheduleController(FixedScheduleService fixedScheduleService,
                                   FixedScheduleConflictPreviewService conflictPreviewService) {
        this.fixedScheduleService = fixedScheduleService;
        this.conflictPreviewService = conflictPreviewService;
    }

    @PostMapping
    @Operation(summary = "고정 일정 생성 (FIX-05·ONB-06)",
            description = "제목·요일·시작/종료 시각(5분 단위) 입력. 생성 시 source=MANUAL·status=ACTIVE. "
                    + "이후 해당 시간은 주간 계획에서 배치 불가로 반영된다.")
    public ResponseEntity<ApiResponse<FixedScheduleResponse>> create(
            @CurrentUser UUID userId,
            @Valid @RequestBody FixedScheduleCreateRequest request) {
        FixedScheduleResponse created = fixedScheduleService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @GetMapping
    @Operation(summary = "고정 일정 목록 (FIX-04)",
            description = "내 고정 일정을 weekday·시작 시각 순으로 반환. status(ACTIVE·INACTIVE) 지정 시 필터.")
    public ApiResponse<List<FixedScheduleResponse>> list(
            @CurrentUser UUID userId,
            @RequestParam(required = false) FixedScheduleStatus status) {
        return ApiResponse.ok(fixedScheduleService.list(userId, status));
    }

    @PatchMapping("/{fixedScheduleId}")
    @Operation(summary = "고정 일정 편집 (FIX-06)",
            description = "제목·요일·시각·기간 전체 교체(version 필수). 부재·타인 → 404, 동시 수정 충돌 → 409(latest 동봉), "
                    + "값 오류 → 422. 편집 시 저장된 주간 계획 배치 불가 시간이 갱신된다.")
    public ApiResponse<FixedScheduleResponse> update(
            @CurrentUser UUID userId,
            @PathVariable UUID fixedScheduleId,
            @Valid @RequestBody FixedScheduleUpdateRequest request) {
        return ApiResponse.ok(fixedScheduleService.update(userId, fixedScheduleId, request));
    }

    @DeleteMapping("/{fixedScheduleId}")
    @Operation(summary = "고정 일정 삭제 (FIX-09)",
            description = "hard delete. 부재·타인 → 404. 주차 예외는 FK CASCADE로 함께 삭제. 성공 204(봉투 없음). "
                    + "삭제 후 주간 계획 재검증(FIX-09)은 검증 엔진 라우트 완성 후.")
    public ResponseEntity<Void> delete(
            @CurrentUser UUID userId,
            @PathVariable UUID fixedScheduleId) {
        fixedScheduleService.delete(userId, fixedScheduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conflict-previews")
    @Operation(summary = "생성·편집 전 충돌 미리보기 (FIX-07·08)",
            description = "후보 고정 일정을 있는 셈 치고 저장된 주간 계획 전량에 V2를 돌린다(무영속 — 아무것도 저장 안 함). "
                    + "충돌이 있는 주만 주차 오름차순으로 반환하고, 후보가 일으킨 V2만 담는다(기존 문제는 제외). "
                    + "candidate.fixedScheduleId 지정 시 편집 — 그 기존 일정은 판정에서 빼고(자기 자신 제외), "
                    + "그 일정에 걸린 주차 예외(PLAN-33)가 있는 주는 건너뛴다. 부재·타인 fixedScheduleId → 404, 값 오류 → 422.")
    public ApiResponse<List<WeekConflictResponse>> previewConflicts(
            @CurrentUser UUID userId,
            @RequestBody ConflictPreviewRequest request) {
        return ApiResponse.ok(conflictPreviewService.preview(userId, request));
    }

    @PostMapping("/{fixedScheduleId}/week-exceptions")
    @Operation(summary = "주차 한정 비활성화 (PLAN-33)",
            description = "그 주만 이 고정 일정을 배치 제약에서 제외(다른 주 무영향). 멱등 — 신규 201·기존 200. "
                    + "고정 일정 부재·타인 → 404.")
    public ResponseEntity<ApiResponse<WeekExceptionResponse>> addWeekException(
            @CurrentUser UUID userId,
            @PathVariable UUID fixedScheduleId,
            @Valid @RequestBody WeekExceptionCreateRequest request) {
        FixedScheduleService.AddWeekExceptionResult result =
                fixedScheduleService.addWeekException(userId, fixedScheduleId, request.weekStartDate());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result.response()));
    }

    @DeleteMapping("/{fixedScheduleId}/week-exceptions/{weekStartDate}")
    @Operation(summary = "주차 한정 재활성화 (PLAN-34)",
            description = "그 주 예외를 제거해 배치 제약을 다시 적용. 멱등 — 예외 부재여도 204. 고정 일정 부재·타인 → 404.")
    public ResponseEntity<Void> removeWeekException(
            @CurrentUser UUID userId,
            @PathVariable UUID fixedScheduleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStartDate) {
        fixedScheduleService.removeWeekException(userId, fixedScheduleId, weekStartDate);
        return ResponseEntity.noContent().build();
    }
}
