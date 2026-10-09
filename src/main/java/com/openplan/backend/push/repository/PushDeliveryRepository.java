package com.openplan.backend.push.repository;

import com.openplan.backend.push.domain.PushDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 발송 멱등 게이트 (ADR-0015 결정 ③). {@link #tryMark}가 유일한 쓰기 진입점이다.
 */
public interface PushDeliveryRepository extends JpaRepository<PushDelivery, UUID> {

    /**
     * 발송 전에 먼저 찍는다 — 삽입되면(1) 이번이 처음이라 보내야 하고, 충돌로 0이 삽입되면 이미 보냈으니
     * 보내지 않는다. {@code ON CONFLICT DO NOTHING}을 쓰는 이유는 {@code FixedScheduleWeekExceptionRepository}와
     * 같다: 유니크 위반 예외를 던지면 트랜잭션이 rollback-only로 마킹돼 커밋 시점에 500이 된다.
     *
     * <p>{@code @Transactional}을 메서드에 직접 둔다 — 호출부({@code PushReminderDispatcher})가 스케줄러
     * 스레드에서 트랜잭션 없이 돌기 때문에, 이 레포지토리 빈 경계에서 트랜잭션을 열어야 "Executing an
     * update/delete query requires a transaction" 오류 없이 네이티브 INSERT가 실행된다 — 대상 1건당
     * 독립 트랜잭션으로 즉시 커밋되는 편이 오히려 결정 ③(보내기 전에 먼저 찍는다)의 의도에 맞는다.
     *
     * @return true면 신규 삽입(발송해야 함), false면 이미 존재(발송 금지 — 중복).
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO push_deliveries (push_delivery_id, user_id, target_type, target_id, start_at, sent_at)
            VALUES (:id, :userId, :targetType, :targetId, :startAt, :sentAt)
            ON CONFLICT (user_id, target_type, target_id, start_at) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId,
                       @Param("targetType") String targetType, @Param("targetId") UUID targetId,
                       @Param("startAt") Instant startAt, @Param("sentAt") Instant sentAt);

    default boolean tryMark(UUID userId, String targetType, UUID targetId, Instant startAt, Instant sentAt) {
        return insertIfAbsent(UUID.randomUUID(), userId, targetType, targetId, startAt, sentAt) == 1;
    }
}
