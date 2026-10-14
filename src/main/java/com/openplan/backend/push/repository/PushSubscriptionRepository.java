package com.openplan.backend.push.repository;

import com.openplan.backend.push.domain.PushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 구독 저장소 (ADR-0015 결정 ④). {@code endpoint} UNIQUE가 "기기당 1행"의 근거다. */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    Optional<PushSubscription> findByEndpoint(String endpoint);

    /**
     * 구독 원자적 업서트 — {@code endpoint} UNIQUE 로 충돌하면 소유자·키·플랫폼을 갈아끼운다(기기가 다른
     * 계정으로 로그인했거나 브라우저가 키를 재발급한 경우). "findByEndpoint 후 없으면 save" 는 같은 새
     * endpoint 로 요청이 거의 동시에 두 번 오면(서비스워커 중복 발화·타임아웃 재시도) 한쪽이 UNIQUE 위반
     * 500이 된다 — {@link PushSettingRepository#upsert}와 같은 이유로 단일 원자문으로 바꿨다(PR #93 리뷰).
     *
     * <p>{@code push_subscription_id}·{@code created_at}·{@code last_success_at}은 기존 행 값을 유지한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO push_subscriptions
                (push_subscription_id, user_id, endpoint, p256dh, auth, platform, created_at)
            VALUES (:id, :userId, :endpoint, :p256dh, :auth, :platform, :now)
            ON CONFLICT (endpoint) DO UPDATE
               SET user_id = EXCLUDED.user_id,
                   p256dh = EXCLUDED.p256dh,
                   auth = EXCLUDED.auth,
                   platform = EXCLUDED.platform
            """, nativeQuery = true)
    void upsert(@Param("id") UUID id,
                @Param("userId") UUID userId,
                @Param("endpoint") String endpoint,
                @Param("p256dh") String p256dh,
                @Param("auth") String auth,
                @Param("platform") String platform,
                @Param("now") Instant now);

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
