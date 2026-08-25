package com.openplan.backend.notification.repository;

import com.openplan.backend.notification.domain.Notification;
import com.openplan.backend.notification.domain.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** 알림 센터 최신순 (A8 인덱스 ix_notifications_user_created 와 정렬 일치). */
    Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** 읽음 처리 — 타인 알림은 조회되지 않아 404 로 은닉된다(NFR-030 과 같은 원칙). */
    Optional<Notification> findByNotificationIdAndUserId(UUID notificationId, UUID userId);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /**
     * 전체 읽음 — 한 문장으로 끝낸다(NOTI-03). 엔티티를 전부 로드해 반복 저장하면 미읽음이 많을수록
     * 비용이 커지고, 그 사이 새 알림이 끼면 일부만 처리된다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * 중복 방지 공통 술어 (ADR-0014) — 같은 유형의 알림이 기준 시각 이후에 이미 있는가.
     * 유형별 "단위"는 호출자가 {@code since} 로 표현한다(날짜당·주당·창당).
     */
    boolean existsByUserIdAndNotificationTypeAndCreatedAtGreaterThanEqual(
            UUID userId, NotificationType type, Instant since);

    /** 대상 엔티티가 있는 유형의 중복 방지 — DEADLINE_SOON(태스크당) 등. */
    boolean existsByUserIdAndNotificationTypeAndRelatedTaskIdAndCreatedAtGreaterThanEqual(
            UUID userId, NotificationType type, UUID relatedTaskId, Instant since);

    boolean existsByUserIdAndNotificationTypeAndRelatedSupportTicketId(
            UUID userId, NotificationType type, UUID relatedSupportTicketId);
}
