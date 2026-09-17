package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.common.Weekday;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.global.time.UserClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 고정 일정의 2개월 회차를 외부와 맞춘다 (#69 계획 D1·D2).
 *
 * <p><b>한 경로가 다섯 가지를 한다.</b> 사용자가 고정 일정을 만들거나 고치거나 지워도, 주차 예외를
 * 걸어도, 그냥 시간이 흘러도 — 전부 «지금 있어야 할 회차 목록» 이 달라지는 일이다. 그래서 훅을
 * 다섯 군데 박지 않고, 동기화 때 <b>있어야 할 것과 있는 것을 견주어</b> 차이만 내보낸다.
 *
 * <ul>
 *   <li>있어야 하는데 없다 → CREATE</li>
 *   <li>있는데 내용이 다르다 → UPDATE (시각만 바뀌면 같은 회차를 고친다 — 지웠다 만들지 않는다)</li>
 *   <li>없어야 하는데 있다 → DELETE (예외가 걸렸거나 패턴이 끝났거나 창 밖으로 나갔다)</li>
 * </ul>
 *
 * <p>🔴 창이 «오늘부터» 라 동기화마다 앞으로 밀린다 — <b>별도 연장 배치가 필요 없다.</b>
 *
 * <p>{@code FixedSchedule} 엔티티에 게터가 없어 투영으로 읽는다 — {@code PlanSnapshotAssembler} 가
 * 고정 일정을 읽을 때 쓰는 것과 같은 관례다(타 도메인 잠정 SQL 접촉).
 */
@Service
public class FixedOccurrenceReconciler {

    private static final Logger log = LoggerFactory.getLogger(FixedOccurrenceReconciler.class);

    private final JdbcTemplate jdbc;
    private final FixedOccurrenceRepository occurrenceRepository;
    private final OutboundCalendarOpRepository opRepository;
    private final UserClock clock;

    public FixedOccurrenceReconciler(JdbcTemplate jdbc, FixedOccurrenceRepository occurrenceRepository,
                                     OutboundCalendarOpRepository opRepository, UserClock clock) {
        this.jdbc = jdbc;
        this.occurrenceRepository = occurrenceRepository;
        this.opRepository = opRepository;
        this.clock = clock;
    }

    /** 이 연동으로 내보낼 회차를 맞춘다. 쓰기 대상 캘린더가 없으면 아무것도 하지 않는다. */
    public void reconcile(UUID userId, ExternalCalendarConnection connection) {
        String calendarId = connection.getWriteCalendarId();
        if (!connection.canWrite() || calendarId == null || calendarId.isBlank()) {
            return;   // 모르면 쓰지 않는다.
        }
        var now = clock.now();
        ZoneId zone = clock.zoneOf(userId);
        LocalDate today = now.atZone(zone).toLocalDate();
        DayOfWeek weekStartsOn = weekStartOf(userId);

        // ① 지금 있어야 할 회차
        Map<String, FixedOccurrencePlanner.Occurrence> wanted = new HashMap<>();
        Map<String, UUID> wantedSchedule = new HashMap<>();
        Map<String, String> wantedTitle = new HashMap<>();
        for (FixedOccurrencePlanner.Pattern pattern : patternsOf(userId)) {
            Set<LocalDate> exceptions = exceptionWeeksOf(pattern.fixedScheduleId());
            for (var occurrence : FixedOccurrencePlanner.expand(pattern, today, zone, exceptions, weekStartsOn)) {
                String uid = OpenPlanEventUid.forFixedOccurrence(pattern.fixedScheduleId(), occurrence.date());
                wanted.put(uid, occurrence);
                wantedSchedule.put(uid, pattern.fixedScheduleId());
                wantedTitle.put(uid, pattern.title());
            }
        }

        // ② 지금 있는 것
        List<FixedOccurrence> stored = occurrenceRepository.findByUserId(userId);
        Set<String> storedUids = new HashSet<>();
        List<FixedOccurrence> gone = new ArrayList<>();
        for (FixedOccurrence o : stored) {
            storedUids.add(o.getExternalUid());
            var want = wanted.get(o.getExternalUid());
            if (want == null) {
                gone.add(o);
                continue;
            }
            String title = wantedTitle.get(o.getExternalUid());
            if (o.neverSent() || o.differsFrom(title, want.startAt(), want.endAt())) {
                enqueue(userId, connection, o, title, want, calendarId,
                        o.neverSent() ? OutboundOperation.CREATE : OutboundOperation.UPDATE, now);
            }
        }

        // ③ 있어야 하는데 없는 것
        for (var entry : wanted.entrySet()) {
            if (storedUids.contains(entry.getKey())) {
                continue;
            }
            FixedOccurrence created = occurrenceRepository.save(FixedOccurrence.reserve(
                    wantedSchedule.get(entry.getKey()), userId, connection.getId(),
                    entry.getValue().date(), now));
            enqueue(userId, connection, created, wantedTitle.get(entry.getKey()), entry.getValue(),
                    calendarId, OutboundOperation.CREATE, now);
        }

        // ④ 없어야 하는데 있는 것 — 예외가 걸렸거나, 패턴이 끝났거나, 창 밖으로 나갔다.
        //    🔴 창 밖으로 나간 것도 지운다. 지난 회차를 외부에 남겨 두면 «과거는 영원히 남는»
        //       캘린더가 되고, 사용자가 손으로 지워야 한다. 창은 오늘부터이므로 지난 것만 나간다.
        for (FixedOccurrence o : gone) {
            enqueueDelete(userId, connection, o, calendarId, now);
            occurrenceRepository.delete(o);
        }
        if (!gone.isEmpty()) {
            log.debug("고정 일정 회차 {}건이 창 밖·예외로 빠져 삭제를 적었다: userId={}", gone.size(), userId);
        }
    }

    private void enqueue(UUID userId, ExternalCalendarConnection connection, FixedOccurrence occurrence,
                         String title, FixedOccurrencePlanner.Occurrence want, String calendarId,
                         OutboundOperation operation, java.time.Instant now) {
        OutboundPayload payload = new OutboundPayload(occurrence.getExternalUid(), title,
                want.startAt(), want.endAt(), calendarId,
                occurrence.getExternalEventId(), occurrence.getResourceHref(), occurrence.getEtag());
        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.FIXED_OCCURRENCE, occurrence.getId(), operation, payload, now));
    }

    private void enqueueDelete(UUID userId, ExternalCalendarConnection connection, FixedOccurrence occurrence,
                               String calendarId, java.time.Instant now) {
        OutboundPayload payload = new OutboundPayload(occurrence.getExternalUid(), null, null, null, calendarId,
                occurrence.getExternalEventId(), occurrence.getResourceHref(), occurrence.getEtag());
        opRepository.save(OutboundCalendarOp.queue(userId, connection.getId(),
                OutboundTargetType.FIXED_OCCURRENCE, occurrence.getId(), OutboundOperation.DELETE, payload, now));
    }

    /** 직접 만든 ACTIVE 고정 일정만. 외부에서 온 것(connection_id 있음)은 원본이 밖에 있어 되돌려 보내지 않는다. */
    private List<FixedOccurrencePlanner.Pattern> patternsOf(UUID userId) {
        return jdbc.query("""
                SELECT fixed_schedule_id, title, weekday, start_time, end_time, start_date, end_date
                  FROM fixed_schedules
                 WHERE user_id = ? AND status = 'ACTIVE' AND connection_id IS NULL
                """,
                (rs, n) -> new FixedOccurrencePlanner.Pattern(
                        rs.getObject("fixed_schedule_id", UUID.class),
                        rs.getString("title"),
                        DayOfWeek.of(Weekday.valueOf(rs.getString("weekday")).ordinal() + 1),
                        rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class)),
                userId);
    }

    private Set<LocalDate> exceptionWeeksOf(UUID fixedScheduleId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT week_start_date FROM fixed_schedule_week_exceptions WHERE fixed_schedule_id = ?
                """, LocalDate.class, fixedScheduleId));
    }

    /** 주 시작 요일 — 주차 예외가 어느 주를 가리키는지가 이 값에 달려 있다. */
    private DayOfWeek weekStartOf(UUID userId) {
        List<String> found = jdbc.queryForList(
                "SELECT week_start_day FROM user_profiles WHERE user_id = ?", String.class, userId);
        if (found.isEmpty() || found.getFirst() == null) {
            return DayOfWeek.MONDAY;
        }
        return DayOfWeek.of(Weekday.valueOf(found.getFirst()).ordinal() + 1);
    }
}
