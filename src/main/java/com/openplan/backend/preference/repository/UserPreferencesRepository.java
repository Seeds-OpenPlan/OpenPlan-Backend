package com.openplan.backend.preference.repository;

import com.openplan.backend.preference.domain.UserPreferences;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/** 사용자 기본 설정 저장소 (FIX-10~12). PK 가 user_id 라 소유 판정이 조회 자체에 들어 있다. */
public interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {

    /**
     * 기본 설정 원자적 업서트(PUT — 있으면 갱신, 없으면 생성). {@code INSERT ... ON CONFLICT (user_id)
     * DO UPDATE} 단일 원자문으로 첫 저장 시 동시 요청의 PK 경합을 흡수한다(리뷰 반영, PR #41).
     *
     * <p><b>왜 "findById 후 없으면 save" 대신 이 방식을 쓰는가</b>: find-or-create 는 원자적이지
     * 않다 — 기본 설정을 처음 저장하는 사용자의 요청이 거의 동시에 두 개 들어오면(더블클릭·재시도·
     * 멀티탭) 둘 다 빈 결과를 보고 둘 다 INSERT를 시도한다. {@code user_id}가 PK라 하나는 성공하고
     * 다른 하나는 PK 위반({@code DataIntegrityViolationException})으로 500이 된다
     * ({@code WbsItemRepository.upsert}와 같은 함정 — 그 쪽 주석에 있는 "saveAndFlush 후 catch"가
     * 왜 안 되는지 근거도 동일하다: 위반 시점에 트랜잭션이 rollback-only로 마킹돼 catch로 삼켜도
     * 커밋 시 {@code UnexpectedRollbackException}이 대신 터진다). {@code ON CONFLICT}는 예외 자체를
     * 없애 두 요청 모두 200으로 수렴시킨다.
     *
     * <p>PUT 은 전체 교체 계약이라 {@code DO UPDATE}(last-write-wins) — {@code created_at}은
     * 최초 삽입 값을 그대로 둔다(EXCLUDED가 아니라 기존 컬럼 유지, {@code UserPreferences.createdAt}이
     * "처음 저장한 시각"이라는 의미를 지키기 위함).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO user_preferences
                (user_id, default_estimated_minutes, default_replan_strategy, weekly_available_minutes,
                 updated_at, created_at)
            VALUES (:userId, :defaultEstimatedMinutes, :defaultReplanStrategy, :weeklyAvailableMinutes,
                    :now, :now)
            ON CONFLICT (user_id) DO UPDATE
               SET default_estimated_minutes = EXCLUDED.default_estimated_minutes,
                   default_replan_strategy = EXCLUDED.default_replan_strategy,
                   weekly_available_minutes = EXCLUDED.weekly_available_minutes,
                   updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("userId") UUID userId,
                @Param("defaultEstimatedMinutes") Integer defaultEstimatedMinutes,
                @Param("defaultReplanStrategy") String defaultReplanStrategy,
                @Param("weeklyAvailableMinutes") Integer weeklyAvailableMinutes,
                @Param("now") Instant now);
}
