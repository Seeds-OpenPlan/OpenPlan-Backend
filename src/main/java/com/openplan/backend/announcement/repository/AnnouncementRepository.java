package com.openplan.backend.announcement.repository;

import com.openplan.backend.announcement.domain.Announcement;
import com.openplan.backend.announcement.domain.AnnouncementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 공지 리포지토리. 공개 노출은 PUBLISHED만. 목록은 게시일 DESC(OPS-01 AC① · 인덱스 ix_announcements_published).
 */
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    Page<Announcement> findByStatusOrderByPublishedStartAtDesc(AnnouncementStatus status, Pageable pageable);

    Optional<Announcement> findByAnnouncementIdAndStatus(UUID announcementId, AnnouncementStatus status);
}
