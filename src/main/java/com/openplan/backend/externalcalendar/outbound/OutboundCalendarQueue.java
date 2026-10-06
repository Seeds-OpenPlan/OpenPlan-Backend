package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.schedule.domain.Schedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
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
     *
     * <p>🔴 <b>이미 매핑이 있으면 그 매핑의 연동으로만 보낸다.</b> 지금 계산한 «쓸 수 있는 연동» 은
     * 그 사이 바뀔 수 있다(먼저 연결한 쪽의 쓰기 권한이 사라지면 다음 연동이 뽑힌다). 섞으면 구글
     * 자격증명으로 애플 캘린더 id 를 지목하게 되고, 구글은 404 를 주며 그것은 «이미 지워짐» 으로
     * 읽혀 <b>밖에 일정이 남은 채 성공으로 기록된다</b>(#79 리뷰 Blocking).
     *
     * <p>🔴 <b>아직 안 나간 작업이 있으면 새로 쌓지 않고 그것을 고친다.</b> CREATE 가 대기 중인데
     * UPDATE 를 따로 쌓으면 그 UPDATE 는 참조(ETag)가 비어 영원히 실패한다(#80 리뷰 Blocking).
     */
    public void enqueueScheduleUpsert(UUID userId, Schedule schedule) {
        Optional<ScheduleExternalRef> existing = refRepository.findById(schedule.getId());
        Optional<ExternalCalendarConnection> target = existing.isPresent()
                ? homeConnection(existing.get())
                : writableConnection(userId);
        if (target.isEmpty()) {
            return;
        }
        ExternalCalendarConnection connection = target.get();
        var now = clock.now();

        boolean isNew = existing.isEmpty();
        ScheduleExternalRef ref = existing.orElseGet(() -> refRepository.save(ScheduleExternalRef.reserve(
                schedule.getId(), connection.getId(), OpenPlanEventUid.forSchedule(schedule.getId()), now)));

        // 이미 내보낸 일정은 «있는 곳» 으로 — 대상 설정이 바뀌어도 그 이벤트는 옛 캘린더에 있다.
        String calendarId = OutboundPayload.targetCalendar(ref.getSentCalendarId(), connection.getWriteCalendarId());
        if (ref.isSent() && calendarId == null) {
            return;   // 밖에 있는데 어디 있는지 모른다 — 엉뚱한 곳을 고치지 않는다.
        }
        OutboundPayload payload = new OutboundPayload(
                ref.getExternalUid(), schedule.getTitle(), schedule.getStartAt(), schedule.getEndAt(),
                calendarId,
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());

        List<OutboundCalendarOp> unsent = opRepository.findUnsentByTarget(OutboundTargetType.SCHEDULE, schedule.getId());
        if (!unsent.isEmpty()) {
            // 대기 중인 CREATE 는 CREATE 로 남는다 — 아직 밖에 없으니 «고칠» 대상이 없다. 내용만 최신으로.
            unsent.get(unsent.size() - 1).replacePayload(payload, now);
            return;
        }
        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.SCHEDULE, schedule.getId(),
                isNew ? OutboundOperation.CREATE : OutboundOperation.UPDATE, payload, now));
    }

    /**
     * 개인 일정이 지워진다.
     *
     * <p>🔴 <b>지우기 전에 불려야 한다.</b> {@code ON DELETE CASCADE} 로 매핑 행이 함께 사라지면
     * "무엇을 외부에서 지워야 하는지" 를 알 수 없게 된다. 그래서 필요한 값을 payload 에 담는다.
     *
     * <p>🔴 <b>아직 밖에 나간 적이 없으면 DELETE 를 쌓지 않고 대기 중인 CREATE 를 거둔다.</b>
     * 그대로 두면 CREATE 가 뒤늦게 성공해 <b>OpenPlan 에서 지운 일정이 외부에 유령으로 남고</b>,
     * 참조 없이 쌓인 DELETE 는 영원히 실패한다(#80 리뷰 Blocking).
     */
    public void enqueueScheduleDelete(UUID userId, UUID scheduleId) {
        Optional<ScheduleExternalRef> found = refRepository.findById(scheduleId);
        if (found.isEmpty()) {
            return;   // 내보낸 적이 없다 — 밖에 지울 것도 없다.
        }
        ScheduleExternalRef ref = found.get();
        List<OutboundCalendarOp> unsent = opRepository.findUnsentByTarget(OutboundTargetType.SCHEDULE, scheduleId);
        if (!ref.isSent()) {
            opRepository.deleteAll(unsent);
            return;
        }
        Optional<ExternalCalendarConnection> home = homeConnection(ref);
        if (home.isEmpty()) {
            return;
        }
        String calendarId = OutboundPayload.targetCalendar(ref.getSentCalendarId(), home.get().getWriteCalendarId());
        if (calendarId == null) {
            return;   // 어디 있는지 모른다 — 엉뚱한 캘린더에서 지우려 들지 않는다.
        }
        var now = clock.now();
        // 밖에 이미 있으니 대기 중인 UPDATE 는 의미가 없다 — 곧 지울 것을 고치러 갈 이유가 없다.
        opRepository.deleteAll(unsent);
        OutboundPayload payload = new OutboundPayload(
                ref.getExternalUid(), null, null, null,
                // 삭제는 특히 «있는 곳» 이어야 한다 — 매핑 행이 CASCADE 로 곧 사라져 나중에 알 길이 없다.
                calendarId,
                ref.getExternalEventId(), ref.getResourceHref(), ref.getEtag());

        opRepository.save(OutboundCalendarOp.queue(userId, ref.getConnectionId(),
                OutboundTargetType.SCHEDULE, scheduleId, OutboundOperation.DELETE, payload, now));
    }

    /**
     * 이미 매핑이 있는 일정의 연동 — <b>그 일정이 원래 나간 곳</b>이다. 쓸 수 없게 됐으면 비어
     * 있다(닿을 수 없는 곳에 보낼 수는 없다). 다른 연동으로 갈아타지 않는다.
     *
     * <p>대상 캘린더는 요구하지 않는다 — 사용자가 내보내기 대상을 비워도 이미 나간 일정은
     * 매핑이 기억하는 캘린더({@code sentCalendarId})에서 고치고 지울 수 있어야 한다.
     */
    private Optional<ExternalCalendarConnection> homeConnection(ScheduleExternalRef ref) {
        return connectionRepository.findById(ref.getConnectionId())
                .filter(ExternalCalendarConnection::canWrite);
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
