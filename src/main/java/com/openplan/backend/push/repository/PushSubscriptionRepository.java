package com.openplan.backend.push.repository;

import com.openplan.backend.push.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 구독 저장소 (ADR-0015 결정 ④). {@code endpoint} UNIQUE가 "기기당 1행"의 근거다. */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    /** 발송 대상(사용자 전 기기). 발송기가 토글이 켜진 사용자마다 조회한다. */
    List<PushSubscription> findByUserId(UUID userId);

    /**
     * 해지(DELETE) — 소유자 스코프(남의 기기는 지우지 못한다). 부재·타인 → 0건(멱등 204).
     *
     * <p>{@code @Transactional}을 명시한다 — {@code PushSubscriptionService.unsubscribe}(서비스 레벨
     * {@code @Transactional})뿐 아니라 {@code PushReminderDispatcher}(트랜잭션 없는 스케줄러 스레드,
     * 404/410 구독 삭제 경로)도 이 메서드를 직접 부른다. 삭제형 파생 쿼리는 트랜잭션 없이 실행하면
     * {@link PushDeliveryRepository#insertIfAbsent}와 같은 이유로 실패한다.
     */
    @Transactional
    long deleteByUserIdAndEndpoint(UUID userId, String endpoint);

    boolean existsByUserId(UUID userId);
}
