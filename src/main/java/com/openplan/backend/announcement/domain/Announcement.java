package com.openplan.backend.announcement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * 공지 엔티티 — {@code announcements}. 사용자 무소속 공개 콘텐츠(스코핑 예외 2테이블 중 하나)라 읽기 전용이며,
 * 관리 UI 없이 Flyway 시드/운영 주입 대상이다. 공개 노출은 {@link AnnouncementStatus#PUBLISHED}만.
 *
 * <p>응답에 필요한 최소 컬럼만 매핑한다(published_end_at·created_at은 미매핑 — validate 모드라 무해).
 */
@Entity
@Table(name = "announcements")
public class Announcement {

    @Id
    @Column(name = "announcement_id", nullable = false, updatable = false)
    private UUID announcementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "announcement_type", nullable = false, length = 20)
    private AnnouncementType announcementType;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "published_start_at", nullable = false)
    private Instant publishedStartAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AnnouncementStatus status;

    /** JPA 전용 기본 생성자. */
    protected Announcement() {
    }

    public UUID getAnnouncementId() {
        return announcementId;
    }

    public AnnouncementType getAnnouncementType() {
        return announcementType;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Instant getPublishedStartAt() {
        return publishedStartAt;
    }

    public AnnouncementStatus getStatus() {
        return status;
    }
}
