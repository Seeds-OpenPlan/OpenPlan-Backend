package com.openplan.backend.notification.service;

import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.notification.domain.Notification;
import com.openplan.backend.notification.dto.NotificationResponse;
import com.openplan.backend.notification.repository.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 알림 센터 (NOTI-02/03/04 · ST-B1-12).
 *
 * <p><b>스케줄러가 없다</b>(ADR-0006 선례). 알림은 조회 진입 시 판정·생성한다 — 그 판정은
 * {@code NotificationGenerator} 가 <b>별도 짧은 트랜잭션</b>으로 수행하고, 이 서비스는 그 뒤의
 * 읽기만 담당한다. 생성과 조회를 한 트랜잭션에 묶지 않는 이유는, 판정이 실패해도
 * <b>이미 쌓인 알림은 보여야</b> 하기 때문이다.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;
    private final UserClock clock;

    public NotificationService(NotificationRepository repository, UserClock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(UUID userId, Pageable pageable) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(NotificationResponse::from);
    }

    @Transactional(readOnly = true)
    public int unreadCount(UUID userId) {
        return Math.toIntExact(repository.countByUserIdAndReadAtIsNull(userId));
    }

    /**
     * 읽음 처리 (NOTI-03) — <b>멱등</b>. 이미 읽은 알림을 다시 눌러도 200 이며 최초 읽은 시각을
     * 덮어쓰지 않는다. 타인·부재 알림은 404 E-COM-004 로 존재를 은닉한다.
     */
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification notification = repository.findByNotificationIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        notification.markRead(clock.now());
    }

    /** 전체 읽음 (NOTI-03) — 이미 다 읽었어도 200(0건 갱신). */
    @Transactional
    public int markAllRead(UUID userId) {
        return repository.markAllRead(userId, clock.now());
    }
}
