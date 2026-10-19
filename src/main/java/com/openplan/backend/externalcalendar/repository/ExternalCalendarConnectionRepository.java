package com.openplan.backend.externalcalendar.repository;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 연동 저장소 (ONB-07 · FIX-13/14/16/17). 전 쿼리는 user_id 스코프(소유자 격리·404 은닉).
 */
public interface ExternalCalendarConnectionRepository extends JpaRepository<ExternalCalendarConnection, UUID> {

    /** 동기화 잠금의 네임스페이스(pg advisory lock 의 첫 키) — 다른 용도의 잠금과 섞이지 않게 고정한다. */
    int SYNC_LOCK_NAMESPACE = 69;

    /** 목록(FIX-13) — 연결 시각 오름차순으로 고정해 화면 순서가 요청마다 흔들리지 않게 한다. */
    List<ExternalCalendarConnection> findByUserIdOrderByConnectedAtAsc(UUID userId);

    /** 소유자 스코프 단건 — 상태 변경·해제·캘린더 조회 공용. 부재·타인 → empty → 404. */
    Optional<ExternalCalendarConnection> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 중복 연결 사전 확인 (FIX-14 · 409 E-EXT-004).
     * 최종 판정은 UQ(user_id, provider, account_identifier)가 한다 — 동시 요청은 제약만이 막는다.
     */
    boolean existsByUserIdAndProviderAndAccountIdentifier(UUID userId, ExternalCalendarProvider provider,
                                                          String accountIdentifier);

    /**
     * 이 사용자의 동기화 잠금을 <b>기다리지 않고</b> 시도한다. 트랜잭션이 끝나면 저절로 풀린다.
     *
     * <p>🔴 기다리면 안 된다 — 기다리는 요청이 연결을 하나씩 쥔 채 서 있으면, 잠금을 쥔 쪽이
     * 안쪽 {@code REQUIRES_NEW} 용 연결을 못 얻어 풀 전체가 멈춘다(2026-10-19 운영 장애).
     * 사용자 해시 충돌은 드물게 한 번 건너뛰는 것으로 끝나므로 허용한다.
     */
    default boolean tryLockSyncFor(UUID userId) {
        return tryAdvisoryXactLock(SYNC_LOCK_NAMESPACE, userId.hashCode());
    }

    @Query(value = "SELECT pg_try_advisory_xact_lock(:namespace, :key)", nativeQuery = true)
    boolean tryAdvisoryXactLock(@Param("namespace") int namespace, @Param("key") int key);
}
