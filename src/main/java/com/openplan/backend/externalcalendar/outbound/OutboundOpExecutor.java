package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.provider.CalendarProvider;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
import com.openplan.backend.externalcalendar.provider.ExternalRef;
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
        // 🔴 매핑 행을 잠그고 보낸다 — 삭제 쪽도 같은 행을 잠그며 읽으므로 둘이 차례를 지킨다.
        //    보내는 중에 삭제가 들어오면 삭제가 이 커밋을 기다렸다가 «나갔음» 을 보고 DELETE 를 적는다.
        //    삭제가 먼저 잡았으면 여기서 기다렸다가, 작업이 거둬졌으면 아무것도 보내지 않는다.
        boolean mapped = lockMapping(op);
        if (!opRepository.existsById(opId)) {
            log.info("잠금을 기다리는 사이 원본이 지워져 작업이 거둬졌다: opId={} target={}", opId, op.getTargetType());
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
            if (connection.getWriteCalendarId() == null || connection.getWriteCalendarId().isBlank()) {
                // 🔴 사용자가 내보내기를 껐다(또는 아직 안 골랐다) — 작업 종류와 무관하게 아무것도 안 보낸다.
                //    끄기 전에 쌓인 UPDATE·DELETE 도 마찬가지다(계약: 해제 이후 변경은 더 나가지 않는다).
                //    FAILED 는 다시 집히므로, 대상을 다시 고르면 이 op 이 그때 나간다 — 버리지 않는다.
                op.fail("대상 캘린더가 없다 — 내보내기가 해제된 상태다", now);
                return;
            }
            if (op.getOperation() == OutboundOperation.CREATE && !mapped) {
                // 🔴 원본이 지워져 매핑이 CASCADE 로 사라졌다. 보내면 결과를 적을 곳이 없어 밖에 아무도
                //    지울 수 없는 유령이 생긴다 — 지운 고정 일정이 9주치로 되살아난 것이 이 경로다(QA F4).
                //    적재 쪽이 지우기 전에 거두는 것이 정본이고, 이것은 그 사이를 빠져나온 것의 안전망이다.
                log.info("원본이 지워진 CREATE 를 거둔다: opId={} target={}", opId, op.getTargetType());
                opRepository.delete(op);
                return;
            }
            CalendarProvider provider = registry.get(connection.getProvider());
            ProviderCredential credential = tokens.usableCredential(connection);
            // 🔴 CREATE 만 «지금 설정» 을 다시 읽는다. 제공자 장애로 CREATE 가 FAILED 로 남아 있는
            //    동안 사용자가 대상을 바꾸면(setWriteCalendar), 적재 때 박아 둔 값으로 보내면 이미 버린
            //    캘린더에 새 일정이 생긴다.
            //    UPDATE·DELETE 는 payload 를 믿는다 — 적재 쪽이 OutboundPayload.targetCalendar 로
            //    «그 이벤트가 실재하는 캘린더» 를 이미 골라 실어 뒀다(매핑의 sentCalendarId). 여기서
            //    지금 설정을 따르면 없는 곳에서 고치려 드는 404 가 영구히 반복된다.
            String calendarId = op.getOperation() == OutboundOperation.CREATE
                    ? connection.getWriteCalendarId()
                    : op.getPayload().writeCalendarId();

            if (calendarId == null || calendarId.isBlank()) {
                // UPDATE·DELETE 인데 payload 에 «있는 곳» 이 없다 — 엉뚱한 곳을 고치지 않는다.
                op.fail("대상 캘린더가 없다 — 이벤트가 있는 캘린더를 모른다", now);
                return;
            }

            switch (op.getOperation()) {
                case CREATE -> recordSent(op, provider.createEvent(credential, calendarId,
                        op.getPayload().toEvent()), calendarId, now);
                case UPDATE -> recordSent(op, provider.updateEvent(credential, calendarId,
                        currentRef(op), op.getPayload().toEvent()), calendarId, now);
                case DELETE -> provider.deleteEvent(credential, calendarId, currentRef(op));
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

    /**
     * 이 작업의 매핑 행을 잠그고, 아직 있는지 답한다 — 없으면 원본이 지워진 것이다.
     *
     * <p>🔴 잠금은 이 트랜잭션이 끝날 때까지 쥔다. 매핑이 있는지 보내기 <b>전에</b> 한 번 보는 것만으로는
     * 보내는 동안 들어온 삭제를 막지 못한다 — 삭제가 «안 나감» 을 보고 CREATE 만 거둔 뒤 매핑을 CASCADE 로
     * 지우면, 방금 밖에 생긴 이벤트의 결과를 적을 곳이 없다(#97 리뷰 Should-fix).
     */
    private boolean lockMapping(OutboundCalendarOp op) {
        return switch (op.getTargetType()) {
            case SCHEDULE -> refRepository.findLockedByScheduleId(op.getTargetId()).isPresent();
            case FIXED_OCCURRENCE -> occurrenceRepository.findLockedById(op.getTargetId()).isPresent();
            case PLAN_BLOCK -> blockRefRepository.findLockedById(op.getTargetId()).isPresent();
        };
    }

    /**
     * 수정·삭제가 지목할 참조. <b>매핑이 남아 있으면 그 최신 값</b>을 쓴다 — payload 는 적재 시점에
     * 얼린 값이라, 그 사이 앞선 작업이나 되받기가 ETag 를 바꿨으면 낡은 ETag 로 영원히 충돌한다.
     * 매핑이 이미 없으면(삭제 CASCADE 뒤) payload 가 유일한 근거다 — 그래서 거기 담아 둔 것이다.
     */
    private ExternalRef currentRef(OutboundCalendarOp op) {
        Optional<ExternalRef> current = switch (op.getTargetType()) {
            case SCHEDULE -> refRepository.findById(op.getTargetId())
                    .map(ref -> new ExternalRef(ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag()));
            case FIXED_OCCURRENCE -> occurrenceRepository.findById(op.getTargetId())
                    .map(o -> new ExternalRef(o.getExternalEventId(), o.getResourceHref(), o.getEtag()));
            case PLAN_BLOCK -> blockRefRepository.findById(op.getTargetId())
                    .map(r -> new ExternalRef(r.getExternalEventId(), r.getResourceHref(), r.getEtag()));
        };
        return current.orElseGet(() -> op.getPayload().toRef());
    }

    /**
     * 보낸 결과를 매핑에 적는다 — 다음 수정의 If-Match 재료이자 되받기의 비교 기준이다.
     *
     * <p><b>어느 캘린더로 보냈는지도 함께 적는다</b>(이슈 #69). 이 값이 없으면 대상 설정이 바뀐 뒤
     * 그 이벤트를 어디서 고쳐야 하는지 아는 곳이 시스템에 하나도 남지 않는다.
     */
    private void recordSent(OutboundCalendarOp op, ProviderWriteResult result, String calendarId, Instant now) {
        switch (op.getTargetType()) {
            case SCHEDULE -> refRepository.findById(op.getTargetId()).ifPresent(ref -> {
                ref.recordSent(result.externalEventId(), result.resourceHref(), result.etag(),
                        op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now);
                ref.locateInCalendar(calendarId);
            });
            case FIXED_OCCURRENCE -> occurrenceRepository.findById(op.getTargetId()).ifPresent(o -> {
                o.recordSent(result.externalEventId(), result.resourceHref(), result.etag(),
                        op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now);
                o.locateInCalendar(calendarId);
            });
            case PLAN_BLOCK -> blockRefRepository.findById(op.getTargetId()).ifPresent(r -> {
                r.recordSent(result.externalEventId(), result.resourceHref(), result.etag(),
                        op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now);
                r.locateInCalendar(calendarId);
            });
        }
    }
}
