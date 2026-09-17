package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.provider.CalendarProvider;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
import com.openplan.backend.externalcalendar.provider.ProviderCredential;
import com.openplan.backend.externalcalendar.provider.ProviderWriteConflictException;
import com.openplan.backend.externalcalendar.provider.ProviderWriteResult;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.externalcalendar.service.ExternalCalendarTokens;
import com.openplan.backend.global.time.UserClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 아웃박스 한 건을 <b>독립 트랜잭션에서</b> 처리한다 (#69).
 *
 * <p><b>왜 별도 빈인가.</b> 🔴 스프링의 프록시 기반 AOP 는 <b>self-invocation 을 가로채지
 * 못한다.</b> {@code OutboundCalendarPusher} 안에서 {@code this.pushOne(...)} 으로 부르면
 * {@code REQUIRES_NEW} 가 <b>통째로 무시되고</b> 바깥 트랜잭션에 얹힌다.
 *
 * <p>그러면 무슨 일이 나는가 — 조회({@code listEvents})는 트랜잭션이고, 그 안에서 밀어내기 뒤에
 * 동기화가 돈다. 구글에 생성을 <b>성공시킨 직후</b>(외부 캘린더에 이벤트가 이미 생겼고 되돌릴 수
 * 없다) 동기화가 제공자 오류로 예외를 던지면 바깥이 통째로 롤백되어, 방금 커밋됐어야 할
 * {@code op.succeed()} 와 매핑 갱신이 함께 사라진다. 다음 동기화는 같은 건을 PENDING 으로 다시
 * 집지만 구글은 이미 있는 iCalUID 에 409 를 주므로 <b>영원히 실패만 반복</b>하고 그 일정은 다시는
 * 동기화되지 않는다(#79 리뷰 Blocking).
 *
 * <p>이 저장소가 같은 함정을 이미 두 번 밟았다 — {@code AuthSessionTerminator} 와
 * {@code ExternalCalendarEventWriter} 가 같은 이유로 별도 빈이다.
 */
@Component
public class OutboundOpExecutor {

    private static final Logger log = LoggerFactory.getLogger(OutboundOpExecutor.class);

    private final OutboundCalendarOpRepository opRepository;
    private final ScheduleExternalRefRepository refRepository;
    private final FixedOccurrenceRepository occurrenceRepository;
    private final PlanBlockExternalRefRepository blockRefRepository;
    private final ExternalCalendarConnectionRepository connectionRepository;
    private final CalendarProviderRegistry registry;
    private final ExternalCalendarTokens tokens;
    private final UserClock clock;

    public OutboundOpExecutor(OutboundCalendarOpRepository opRepository,
                              ScheduleExternalRefRepository refRepository,
                              FixedOccurrenceRepository occurrenceRepository,
                              PlanBlockExternalRefRepository blockRefRepository,
                              ExternalCalendarConnectionRepository connectionRepository,
                              CalendarProviderRegistry registry,
                              ExternalCalendarTokens tokens, UserClock clock) {
        this.opRepository = opRepository;
        this.refRepository = refRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.blockRefRepository = blockRefRepository;
        this.connectionRepository = connectionRepository;
        this.registry = registry;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 한 건. 성공·실패 모두 결과를 남기고 <b>즉시 커밋</b>한다 — 남기지 않으면 다음 동기화가
     * 같은 것을 무한히 다시 보낸다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void execute(UUID opId) {
        OutboundCalendarOp op = opRepository.findById(opId).orElse(null);
        if (op == null || op.getStatus() == OutboundStatus.DONE) {
            return;
        }
        Instant now = clock.now();
        try {
            Optional<ExternalCalendarConnection> found = connectionRepository.findById(op.getConnectionId());
            if (found.isEmpty() || !found.get().canWrite()) {
                op.fail("연동이 없거나 쓰기 권한이 없다", now);
                return;
            }
            ExternalCalendarConnection connection = found.get();
            CalendarProvider provider = registry.get(connection.getProvider());
            ProviderCredential credential = tokens.usableCredential(connection);
            String calendarId = op.getPayload().writeCalendarId();

            switch (op.getOperation()) {
                case CREATE -> recordSent(op, provider.createEvent(credential, calendarId,
                        op.getPayload().toEvent()), now);
                case UPDATE -> recordSent(op, provider.updateEvent(credential, calendarId,
                        op.getPayload().toRef(), op.getPayload().toEvent()), now);
                case DELETE -> provider.deleteEvent(credential, calendarId, op.getPayload().toRef());
            }
            op.succeed(now);
        } catch (ProviderWriteConflictException e) {
            // «덮지 않았다» 이지 장애가 아니다. 재시도하면 방금 막은 덮어쓰기를 손으로 하는 셈이다.
            log.info("외부에서 먼저 바뀌어 내보내지 않았다: opId={} {}", opId, e.getMessage());
            op.fail("외부 변경과 충돌 — 덮지 않았다: " + e.getMessage(), now);
        } catch (Exception e) {
            log.warn("외부 캘린더 쓰기 실패: opId={} op={}", opId, op.getOperation(), e);
            op.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), now);
        }
    }

    /** 보낸 결과를 매핑에 적는다 — 다음 수정의 If-Match 재료이자 되받기의 비교 기준이다. */
    private void recordSent(OutboundCalendarOp op, ProviderWriteResult result, Instant now) {
        switch (op.getTargetType()) {
            case SCHEDULE -> refRepository.findById(op.getTargetId()).ifPresent(ref -> ref.recordSent(
                    result.externalEventId(), result.resourceHref(), result.etag(),
                    op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now));
            case FIXED_OCCURRENCE -> occurrenceRepository.findById(op.getTargetId()).ifPresent(o -> o.recordSent(
                    result.externalEventId(), result.resourceHref(), result.etag(),
                    op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now));
            case PLAN_BLOCK -> blockRefRepository.findById(op.getTargetId()).ifPresent(r -> r.recordSent(
                    result.externalEventId(), result.resourceHref(), result.etag(),
                    op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now));
        }
    }
}
