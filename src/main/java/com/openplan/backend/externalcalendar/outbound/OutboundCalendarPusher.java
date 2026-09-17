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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 아웃박스를 실제로 밀어낸다 (#69 · 계획 D2·D5).
 *
 * <p><b>언제 도는가.</b> 별도 배치가 없다 — 동기화가 돌 때 함께 돈다. 이 저장소에는
 * {@code @Scheduled} 가 하나도 없고, ADR-0006 이 «단일 EC2 에서 배치 실패·중복 실행 관측 부담» 을
 * 들어 배치를 기각했다. 그 결정을 뒤집지 않는다.
 *
 * <p>🔴 <b>한 건의 실패가 나머지를 막지 않는다.</b> 각 건을 독립 트랜잭션으로 처리하고, 실패는
 * 그 행에 사유를 적고 넘어간다. 한 덩어리로 묶으면 일정 하나가 충돌했을 때 그 뒤 전부가
 * 밀리고, 사용자는 «어제 것부터 아무것도 안 나갔다» 를 보게 된다.
 */
@Service
public class OutboundCalendarPusher {

    private static final Logger log = LoggerFactory.getLogger(OutboundCalendarPusher.class);

    private final OutboundCalendarOpRepository opRepository;
    private final ScheduleExternalRefRepository refRepository;
    private final FixedOccurrenceRepository occurrenceRepository;
    private final ExternalCalendarConnectionRepository connectionRepository;
    private final CalendarProviderRegistry registry;
    private final ExternalCalendarTokens tokens;
    private final UserClock clock;

    public OutboundCalendarPusher(OutboundCalendarOpRepository opRepository,
                                  ScheduleExternalRefRepository refRepository,
                                  FixedOccurrenceRepository occurrenceRepository,
                                  ExternalCalendarConnectionRepository connectionRepository,
                                  CalendarProviderRegistry registry,
                                  ExternalCalendarTokens tokens, UserClock clock) {
        this.opRepository = opRepository;
        this.refRepository = refRepository;
        this.occurrenceRepository = occurrenceRepository;
        this.connectionRepository = connectionRepository;
        this.registry = registry;
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 이 사용자의 대기열을 <b>만든 순서대로</b> 밀어낸다.
     *
     * <p>순서가 중요하다 — 같은 대상에 CREATE 뒤 DELETE 가 쌓였는데 거꾸로 보내면 <b>지운 일정이
     * 되살아난다.</b>
     *
     * <p>🔴 이 메서드는 <b>예외를 올리지 않는다.</b> 동기화(조회) 경로에서 불리는데, 외부 쓰기가
     * 실패했다고 사용자의 조회가 실패하면 «남의 장애로 내 화면이 깨지는» 것이다.
     */
    public void pushPending(UUID userId) {
        List<OutboundCalendarOp> pending = opRepository.findPending(userId);
        if (pending.isEmpty()) {
            return;
        }
        for (OutboundCalendarOp op : pending) {
            try {
                pushOne(op.getId());
            } catch (Exception e) {
                // pushOne 이 이미 사유를 적고 커밋했다. 여기서 다시 올리면 조회가 깨진다.
                log.warn("외부 캘린더 내보내기 실패 — 다음 동기화에서 다시 시도한다: opId={}", op.getId());
            }
        }
    }

    /**
     * 한 건. <b>독립 트랜잭션</b>이라 실패해도 앞뒤가 되돌아가지 않는다.
     *
     * <p>성공·실패 모두 아웃박스 행에 결과를 남기고 커밋한다 — 남기지 않으면 다음 동기화가
     * 같은 것을 무한히 다시 보낸다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pushOne(UUID opId) {
        OutboundCalendarOp op = opRepository.findById(opId).orElse(null);
        if (op == null || op.getStatus() == OutboundStatus.DONE) {
            return;
        }
        var now = clock.now();
        try {
            Optional<ExternalCalendarConnection> found = connectionRepository.findById(op.getConnectionId());
            if (found.isEmpty() || !found.get().canWrite()) {
                // 연동이 끊겼거나 권한이 회수됐다. 사용자가 다시 연결하면 풀리므로 포기하지 않는다.
                op.fail("연동이 없거나 쓰기 권한이 없다", now);
                return;
            }
            ExternalCalendarConnection connection = found.get();
            CalendarProvider provider = registry.get(connection.getProvider());
            ProviderCredential credential = tokens.usableCredential(connection);
            String calendarId = op.getPayload().writeCalendarId();

            switch (op.getOperation()) {
                case CREATE -> {
                    ProviderWriteResult r = provider.createEvent(credential, calendarId, op.getPayload().toEvent());
                    recordSent(op, r, now);
                }
                case UPDATE -> {
                    ProviderWriteResult r = provider.updateEvent(credential, calendarId,
                            op.getPayload().toRef(), op.getPayload().toEvent());
                    recordSent(op, r, now);
                }
                case DELETE -> {
                    provider.deleteEvent(credential, calendarId, op.getPayload().toRef());
                    // 매핑은 원본과 함께 CASCADE 로 이미 사라졌다 — 여기서 지울 것이 없다.
                }
            }
            op.succeed(now);
        } catch (ProviderWriteConflictException e) {
            // 🔴 «덮지 않았다» 이지 장애가 아니다. 재시도하면 방금 막은 덮어쓰기를 손으로 하는 셈이라
            //    사유만 남기고 멈춘다 — 다음 조회가 외부 값을 읽어 오면 되받기가 그것을 처리한다.
            log.info("외부에서 먼저 바뀌어 내보내지 않았다: opId={} {}", op.getId(), e.getMessage());
            op.fail("외부 변경과 충돌 — 덮지 않았다: " + e.getMessage(), now);
        } catch (Exception e) {
            log.warn("외부 캘린더 쓰기 실패: opId={} op={}", op.getId(), op.getOperation(), e);
            op.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), now);
        }
    }

    /** 보낸 결과를 매핑에 적는다 — 다음 수정의 If-Match 재료이자 되받기의 비교 기준이다. */
    private void recordSent(OutboundCalendarOp op, ProviderWriteResult result, java.time.Instant now) {
        switch (op.getTargetType()) {
            case SCHEDULE -> refRepository.findById(op.getTargetId()).ifPresent(ref -> ref.recordSent(
                    result.externalEventId(), result.resourceHref(), result.etag(),
                    op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now));
            case FIXED_OCCURRENCE -> occurrenceRepository.findById(op.getTargetId()).ifPresent(o -> o.recordSent(
                    result.externalEventId(), result.resourceHref(), result.etag(),
                    op.getPayload().title(), op.getPayload().startAt(), op.getPayload().endAt(), now));
            case PLAN_BLOCK -> {
                // 5단계에서 자기 매핑에 적는다.
            }
        }
    }
}
