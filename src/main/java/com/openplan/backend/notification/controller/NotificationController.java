package com.openplan.backend.notification.controller;

import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.response.PageMeta;
import com.openplan.backend.global.security.CurrentUser;
import com.openplan.backend.notification.dto.NotificationResponse;
import com.openplan.backend.notification.dto.NotificationSettingResponse;
import com.openplan.backend.notification.dto.SaveNotificationSettingsRequest;
import com.openplan.backend.notification.service.NotificationGenerator;
import com.openplan.backend.notification.service.NotificationService;
import com.openplan.backend.notification.service.NotificationSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 알림 5 EP (NOTI-01~04 · ST-B1-12). 경로는 {@code /api/v1} 접두 없이 매핑한다 — {@code WebConfig}가 부여.
 *
 * <p>설정 2 EP 는 {@code /users/me/...} 아래에 있지만 알림 도메인이라 여기 둔다 — 소유 도메인을
 * 기준으로 묶는 편이 유형이 늘 때 함께 움직인다.
 */
@RestController
@Tag(name = "notifications", description = "알림 센터·설정 (NOTI-01~04)")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationService notificationService;
    private final NotificationSettingService settingService;
    private final NotificationGenerator generator;

    public NotificationController(NotificationService notificationService,
                                  NotificationSettingService settingService,
                                  NotificationGenerator generator) {
        this.notificationService = notificationService;
        this.settingService = settingService;
        this.generator = generator;
    }

    @GetMapping("/notifications")
    @Operation(summary = "알림 센터 (최신순 + 미읽음 수) — NOTI-02/04")
    public ApiResponse<List<NotificationResponse>> list(@CurrentUser UUID userId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        // 1-base 요청을 Spring Data 0-base로. 범위 밖 값은 계약(min1/max100)에 맞춰 클램프.
        generate(userId);
        int pageIndex = Math.max(1, page) - 1;
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Page<NotificationResponse> result = notificationService.list(userId, PageRequest.of(pageIndex, pageSize));
        return ApiResponse.ok(result.getContent(), PageMeta.from(result), notificationService.unreadCount(userId));
    }

    /**
     * 판정은 별도 트랜잭션이며 <b>실패해도 조회를 막지 않는다</b>(ADR-0014). 알림 센터를 못 여는 것보다
     * 새 알림이 한 박자 늦는 편이 낫다 — 다음 진입에서 같은 판정이 다시 돈다(재판정은 no-op).
     */
    private void generate(UUID userId) {
        try {
            generator.generateFor(userId);
        } catch (RuntimeException e) {
            log.warn("notification generation failed: userId={}", userId, e);
        }
    }

    @PatchMapping("/notifications/{notificationId}/read")
    @Operation(summary = "읽음 처리 (NOTI-03) — 멱등. 타인·부재는 404 은닉")
    public ApiResponse<Void> markRead(@CurrentUser UUID userId, @PathVariable UUID notificationId) {
        notificationService.markRead(userId, notificationId);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/notifications/read-all")
    @Operation(summary = "전체 읽음 (NOTI-03) — 미읽음 0건이어도 200")
    public ApiResponse<Void> markAllRead(@CurrentUser UUID userId) {
        notificationService.markAllRead(userId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/users/me/notification-settings")
    @Operation(summary = "알림 설정 조회 (NOTI-01) — 5유형. 최초 조회 시 기본 켜짐으로 시드")
    public ApiResponse<List<NotificationSettingResponse>> getSettings(@CurrentUser UUID userId) {
        return ApiResponse.ok(settingService.getSettings(userId));
    }

    @PutMapping("/users/me/notification-settings")
    @Operation(summary = "알림 설정 저장 (NOTI-01) — 보낸 유형만 반영(부분 저장)")
    public ApiResponse<List<NotificationSettingResponse>> saveSettings(
            @CurrentUser UUID userId, @Valid @RequestBody SaveNotificationSettingsRequest request) {
        return ApiResponse.ok(settingService.saveSettings(userId, request));
    }
}
