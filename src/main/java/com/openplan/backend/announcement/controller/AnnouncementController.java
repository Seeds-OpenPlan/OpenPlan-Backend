package com.openplan.backend.announcement.controller;

import com.openplan.backend.announcement.dto.AnnouncementResponse;
import com.openplan.backend.announcement.service.AnnouncementService;
import com.openplan.backend.global.response.ApiResponse;
import com.openplan.backend.global.response.PageMeta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 공지 컨트롤러(ST-B1-14 · OPS-01/02).
 *
 * <p>사용자 무소속 공개 콘텐츠라 인증·스코핑이 없다(openapi {@code security:[]} — SecurityConfig 공개 경로).
 * 경로는 접두 없이 매핑하고 프레임워크가 {@code /api/v1}을 부여한다.
 */
@RestController
@Tag(name = "announcement", description = "공지·랜딩 (OPS-01/02)")
public class AnnouncementController {

    /** 페이지 크기 상한(openapi Size max) — 초과 요청은 이 값으로 클램프한다. */
    private static final int MAX_PAGE_SIZE = 100;

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping("/announcements")
    @Operation(summary = "공지 목록 (OPS-01) — 공개, 게시일 DESC")
    public ApiResponse<List<AnnouncementResponse>> listAnnouncements(@RequestParam(defaultValue = "1") int page,
                                                                     @RequestParam(defaultValue = "20") int size) {
        // 1-base 요청을 Spring Data 0-base로. 범위 밖 값은 계약(min1/max100)에 맞춰 클램프.
        int pageIndex = Math.max(1, page) - 1;
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Page<AnnouncementResponse> result = announcementService.getPublished(PageRequest.of(pageIndex, pageSize));
        return ApiResponse.ok(result.getContent(), PageMeta.from(result));
    }

    @GetMapping("/announcements/{announcementId}")
    @Operation(summary = "공지 상세 (OPS-02) — 공개, 미게시는 404 은닉")
    public ApiResponse<AnnouncementResponse> getAnnouncement(@PathVariable UUID announcementId) {
        return ApiResponse.ok(announcementService.getPublished(announcementId));
    }
}
