package com.openplan.backend.externalcalendar.repository;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 연동 저장소 (ONB-07 · FIX-13/14/16/17). 전 쿼리는 user_id 스코프(소유자 격리·404 은닉).
 */
public interface ExternalCalendarConnectionRepository extends JpaRepository<ExternalCalendarConnection, UUID> {

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
}
