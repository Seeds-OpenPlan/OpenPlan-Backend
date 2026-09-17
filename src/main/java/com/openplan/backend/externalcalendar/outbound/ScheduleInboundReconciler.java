package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.schedule.domain.Schedule;
import com.openplan.backend.schedule.repository.ScheduleRepository;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 외부에서 고쳐지거나 지워진 <b>우리 일정</b>을 되받는다 (#69 계획 D4).
 *
 * <p>에코 차단은 «우리 것을 새 후보로 만들지 않는다» 까지다. <b>변경은 본다</b> — 그 둘을 한
 * 규칙으로 묶으면 사용자가 폰에서 고친 것이 영영 돌아오지 않는다.
 *
 * <p><b>무엇을 기준으로 «고쳐졌다» 고 하는가.</b> 내보낼 때 적어 둔 스냅샷
 * ({@code schedule_external_refs.sent_*})과 지금 외부 값을 비교한다. 우리가 보낸 그대로면
 * 그것은 우리 쓰기가 돌아온 것이지 사용자의 편집이 아니다.
 */
@Service
public class ScheduleInboundReconciler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleInboundReconciler.class);

    private final ScheduleExternalRefRepository refRepository;
    private final ScheduleRepository scheduleRepository;
    private final PlanBlockRepository planBlockRepository;
    private final WeeklyPlanRepository weeklyPlanRepository;
    private final UserClock clock;

    public ScheduleInboundReconciler(ScheduleExternalRefRepository refRepository,
                                     ScheduleRepository scheduleRepository,
                                     PlanBlockRepository planBlockRepository,
                                     WeeklyPlanRepository weeklyPlanRepository,
                                     UserClock clock) {
        this.refRepository = refRepository;
        this.scheduleRepository = scheduleRepository;
        this.planBlockRepository = planBlockRepository;
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.clock = clock;
    }

    /**
     * 우리 UID 로 돌아온 일정 한 건.
     *
     * <p>고쳐졌으면 반영하고, 어느 쪽이든 <b>ETag 는 갱신한다</b> — 그 값이 다음 쓰기의
     * {@code If-Match} 재료다. 갱신하지 않으면 다음 수정이 «남이 고쳤다» 로 튕긴다.
     */
    public void reconcileOne(UUID userId, String uid, String title, Instant startAt, Instant endAt,
                             String externalEventId, String resourceHref, String etag) {
        Optional<ScheduleExternalRef> found = refRepository.findByExternalUid(uid);
        if (found.isEmpty()) {
            // 우리 UID 인데 매핑이 없다 — 일정을 지웠는데 외부 삭제가 아직 안 나갔거나, 매핑을 잃었다.
            // 어느 쪽이든 여기서 새로 만들지 않는다. 지어내면 사용자가 지운 일정이 되살아난다.
            log.debug("우리 UID 인데 매핑이 없다 — 건너뛴다: uid={}", uid);
            return;
        }
        ScheduleExternalRef ref = found.get();
        Instant now = clock.now();

        if (!ref.changedOutside(title, startAt, endAt)) {
            // 우리가 보낸 그대로다 — 우리 쓰기가 돌아온 것이지 사용자의 편집이 아니다.
            ref.recordSent(externalEventId, resourceHref, etag, title, startAt, endAt, now);
            return;
        }

        scheduleRepository.findById(ref.getScheduleId()).ifPresent(schedule -> {
            applyExternalEdit(userId, schedule, title, startAt, endAt);
            // 🔴 스냅샷을 **새 값으로** 갱신한다. 안 하면 다음 회차에도 «고쳐졌다» 로 읽혀
            //    같은 편집을 무한히 다시 적용한다.
            ref.recordSent(externalEventId, resourceHref, etag, title, startAt, endAt, now);
            log.info("외부 편집을 되받았다: scheduleId={}", schedule.getId());
        });
    }

    /**
     * 일정과 <b>그 블록</b>을 함께 옮긴다.
     *
     * <p>🔴 주를 넘는 이동은 블록을 <b>떼어낸다</b>(미배치로 되돌린다). 다른 주 계획으로 옮기려면
     * 그 주 계획을 만들거나 찾아야 하고, 옮긴 자리가 겹침·가용시간 밖일 수 있다 — 그 판정은
     * 규칙 엔진의 몫이지 동기화가 조용히 할 일이 아니다. 사용자가 그 주에서 다시 배치한다.
     *
     * <p>같은 주 안의 이동은 블록을 따라 옮기고 <b>확정을 푼다</b> — 그래야 기존 검증 루프가
     * 겹침을 잡아 화면에 배지로 띄운다(계획 D4: 새 충돌 화면을 만들지 않는다).
     */
    private void applyExternalEdit(UUID userId, Schedule schedule, String title, Instant startAt, Instant endAt) {
        Instant oldStart = schedule.getStartAt();
        schedule.relocatedFromExternal(title, startAt, endAt);

        Optional<PlanBlock> block = planBlockRepository.findByScheduleId(schedule.getId());
        if (block.isEmpty()) {
            return;   // 아직 배치되지 않은 일정 — 옮길 블록이 없다.
        }
        PlanBlock b = block.get();
        weeklyPlanRepository.findById(b.getWeeklyPlanId()).ifPresent(plan -> {
            if (sameWeek(plan, oldStart, startAt)) {
                plan.reopenToDraftIfConfirmed();   // 편집이 일어났다 — 기존 검증 루프에 태운다
                planBlockRepository.reschedule(b.getId(), startAt, endAt, plan.getId());
            } else {
                plan.reopenToDraftIfConfirmed();
                planBlockRepository.delete(b);
                log.info("외부 이동이 주를 넘어 블록을 떼어냈다 — 사용자가 새 주에서 다시 배치한다: scheduleId={}",
                        schedule.getId());
            }
        });
    }

    /** 새 시각이 그 블록이 속한 주 계획의 주에 그대로 들어오는가. */
    private boolean sameWeek(WeeklyPlan plan, Instant oldStart, Instant newStart) {
        ZoneId zone = clock.zoneOf(plan.getUserId());
        LocalDate weekStart = plan.getWeekStartDate();
        LocalDate newDate = newStart.atZone(zone).toLocalDate();
        long offset = ChronoUnit.DAYS.between(weekStart, newDate);
        return offset >= 0 && offset < 7;
    }

    /**
     * 이번 회차에 안 온 우리 일정 — 외부에서 지워졌다.
     *
     * <p>🔴 «없음» 의 원인이 여럿이라 그대로 삭제로 읽으면 안 된다. 동기화 창 밖의 일정은
     * <b>원래 안 보이고</b>, 연동이 잠시 실패하면 빈 목록이 온다. 그래서 <b>창 안에 있어야 하는데
     * 없을 때만</b> 지운다 — #70 리뷰가 세운 «귀속을 못 하면 지우지 않는다» 와 같은 원칙이다.
     */
    public void propagateDeletions(UUID connectionId, Set<String> seenUids, Instant from, Instant to) {
        List<ScheduleExternalRef> refs = refRepository.findByConnectionId(connectionId);
        for (ScheduleExternalRef ref : refs) {
            if (seenUids.contains(ref.getExternalUid())) {
                continue;
            }
            if (!ref.wasSentWithin(from, to)) {
                continue;   // 창 밖 — 원래 안 보인다. 없다고 지우면 멀쩡한 일정을 지운다.
            }
            scheduleRepository.findById(ref.getScheduleId()).ifPresent(schedule -> {
                planBlockRepository.findByScheduleId(schedule.getId()).ifPresent(planBlockRepository::delete);
                scheduleRepository.delete(schedule);   // 매핑은 CASCADE 로 함께 사라진다
                log.info("외부에서 지워진 일정을 정리했다: scheduleId={}", schedule.getId());
            });
        }
    }
}
