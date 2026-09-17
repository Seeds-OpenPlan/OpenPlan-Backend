package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.schedule.domain.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 밖으로 내보낼 변경을 <b>적기만</b> 한다 (#69 · 계획 D5).
 *
 * <p>🔴 <b>여기서 외부를 부르지 않는다.</b> 일정 저장이 구글 응답을 기다리면, 구글이 느리거나
 * 죽었을 때 OpenPlan 의 저장이 같이 실패한다 — 사용자에게는 "내 앱이 고장났다" 로 보인다.
 * 실제 호출은 {@link OutboundCalendarPusher} 가 다음 동기화에서 한다.
 *
 * <p><b>조용히 안 넣는 경우가 셋 있고, 전부 의도한 것이다.</b>
 * <ul>
 *   <li>연동이 없다 — 내보낼 곳이 없다.</li>
 *   <li>쓰기 권한이 없다({@code canWrite()}) — 스코프를 넓히기 전에 연동했거나 사용자가 회수했다.</li>
 *   <li>대상 캘린더를 안 골랐다({@code write_calendar_id} 가 null) — <b>모르면 쓰지 않는다.</b>
 *       아무 캘린더나 고르면 사용자가 모르는 곳에 일정이 생긴다.</li>
 * </ul>
 * 셋 다 «아직 아니다» 이지 오류가 아니므로 예외를 던지지 않는다. 일정 저장은 그대로 성공해야 한다.
 */
@Service
public class OutboundCalendarQueue {

    private static final Logger log = LoggerFactory.getLogger(OutboundCalendarQueue.class);

    private final OutboundCalendarOpRepository opRepository;
    private final ScheduleExternalRefRepository refRepository;
    private final ExternalCalendarConnectionRepository connectionRepository;
    private final UserClock clock;

    public OutboundCalendarQueue(OutboundCalendarOpRepository opRepository,
                                 ScheduleExternalRefRepository refRepository,
                                 ExternalCalendarConnectionRepository connectionRepository,
                                 UserClock clock) {
        this.opRepository = opRepository;
        this.refRepository = refRepository;
        this.connectionRepository = connectionRepository;
        this.clock = clock;
    }

    /**
     * 개인 일정이 생기거나 바뀌었다.
     *
     * <p>매핑이 없으면 UID 를 발급해 CREATE 로, 있으면 UPDATE 로 적는다. UID 는 <b>일정 id 에서
     * 파생</b>하므로(개인 일정은 재생성되지 않는다) 같은 일정이 두 UID 를 갖는 일이 없다.
     */
    public void enqueueScheduleUpsert(UUID userId, Schedule schedule) {
        Optional<ExternalCalendarConnection> target = writableConnection(userId);
        if (target.isEmpty()) {
            return;
        }
        ExternalCalendarConnection connection = target.get();
        var now = clock.now();

        Optional<ScheduleExternalRef> existing = refRepository.findById(schedule.getId());
        boolean isNew = existing.isEmpty();
        ScheduleExternalRef ref = existing.orElseGet(() -> refRepository.save(ScheduleExternalRef.reserve(
                schedule.getId(), connection.getId(), OpenPlanEventUid.forSchedule(schedule.getId()), now)));

        OutboundPayload payload = new OutboundPayload(
                ref.getExternalUid(), schedule.getTitle(), schedule.getStartAt(), schedule.getEndAt(),
                connection.getWriteCalendarId(),
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());

        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.SCHEDULE, schedule.getId(),
                isNew ? OutboundOperation.CREATE : OutboundOperation.UPDATE, payload, now));
    }

    /**
     * 개인 일정이 지워진다.
     *
     * <p>🔴 <b>지우기 전에 불려야 한다.</b> {@code ON DELETE CASCADE} 로 매핑 행이 함께 사라지면
     * "무엇을 외부에서 지워야 하는지" 를 알 수 없게 된다. 그래서 필요한 값을 payload 에 담는다.
     */
    public void enqueueScheduleDelete(UUID userId, UUID scheduleId) {
        Optional<ScheduleExternalRef> found = refRepository.findById(scheduleId);
        if (found.isEmpty()) {
            return;   // 내보낸 적이 없다 — 밖에 지울 것도 없다.
        }
        ScheduleExternalRef ref = found.get();
        Optional<ExternalCalendarConnection> target = writableConnection(userId);
        if (target.isEmpty()) {
            return;
        }
        var now = clock.now();
        OutboundPayload payload = new OutboundPayload(
                ref.getExternalUid(), null, null, null,
                target.get().getWriteCalendarId(),
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());

        opRepository.save(OutboundCalendarOp.queue(userId, ref.getConnectionId(),
                OutboundTargetType.SCHEDULE, scheduleId, OutboundOperation.DELETE, payload, now));
    }

    /** 내보낼 수 있는 연동 하나. 여럿이면 가장 먼저 연결한 것 — 대상 선택은 설정 화면의 몫이다. */
    private Optional<ExternalCalendarConnection> writableConnection(UUID userId) {
        return connectionRepository.findByUserIdOrderByConnectedAtAsc(userId).stream()
                .filter(ExternalCalendarConnection::canWrite)
                .filter(c -> {
                    if (c.getWriteCalendarId() == null || c.getWriteCalendarId().isBlank()) {
                        log.debug("쓰기 대상 캘린더를 고르지 않아 내보내지 않는다: connectionId={}", c.getId());
                        return false;
                    }
                    return true;
                })
                .findFirst();
    }
}
