package com.openplan.backend.announcement.dto;

import com.openplan.backend.announcement.domain.Announcement;
import com.openplan.backend.announcement.domain.AnnouncementType;

import java.time.Instant;
import java.util.UUID;

/**
 * 공지 응답 — 목록(OPS-01)·상세(OPS-02) 공용.
 *
 * <p>⚠️ 계약 diff(openapi 정정 대상): openapi {@code Announcement} 스키마엔 {@code announcementType}가 없으나,
 * OPS-01 원문 "목록(<b>유형</b>·제목·게시일)" + ST-B1-14 AC③ "공지 유형 Badge enum 고정"이 이를 요구한다.
 * US/확정계약을 정본으로 삼아 {@code announcementType}를 포함한다(openapi에 enum 추가 정정 필요 — W6 3자 대조).
 */
public record AnnouncementResponse(UUID announcementId, AnnouncementType announcementType,
                                   String title, String content, Instant publishedStartAt) {

    public static AnnouncementResponse from(Announcement a) {
        return new AnnouncementResponse(a.getAnnouncementId(), a.getAnnouncementType(),
                a.getTitle(), a.getContent(), a.getPublishedStartAt());
    }
}
