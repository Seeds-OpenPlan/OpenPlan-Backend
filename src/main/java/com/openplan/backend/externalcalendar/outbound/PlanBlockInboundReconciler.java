package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.global.time.UserClock;
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
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 외부에서 고치거나 지운 <b>태스크 블록</b>을 되받는다 (#69 계획 D4 · 6단계).
 *
 * <p>🔴 <b>새 충돌 화면을 만들지 않는다.</b> 필요한 조각이 이미 전부 있다 —
 * <ul>
 *   <li>편집하면 확정 풀기 → {@code reopenToDraftIfConfirmed()}</li>
 *   <li>위반을 보이기 → 검증 배지({@code validationSummary}) + 검토 패널</li>
 *   <li>위반이면 확정 막기 → 확정 시 BLOCK ≥1 이면 409 {@code E-PLAN-004}</li>
 * </ul>
 * 그래서 <b>블록을 옮기고 확정을 푸는 것</b>까지만 한다. 나머지는 기존 검증 루프가 한다.
 *
 * <p>규칙 7종 중 BLOCK 은 {@code V1_OVERLAP}·{@code V2_FIXED_CONFLICT} 둘뿐이고 나머지 다섯은
 * WARNING 이다. 즉 폰에서 옮긴 자리는 <b>대부분 경고로 뜨고 계획은 그대로 선다</b> — 확정이
 * 막히는 것은 겹침·고정일정 충돌 두 경우다.
 */
@Service
public class PlanBlockInboundReconciler {

    private static final Logger log = LoggerFactory.getLogger(PlanBlockInboundReconciler.class);

    private final PlanBlockExternalRefRepository refRepository;
    private final PlanBlockRepository planBlockRepository;
    private final WeeklyPlanRepository weeklyPlanRepository;
    private final UserClock clock;

    public PlanBlockInboundReconciler(PlanBlockExternalRefRepository refRepository,
                                      PlanBlockRepository planBlockRepository,
                                      WeeklyPlanRepository weeklyPlanRepository,
                                      UserClock clock) {
        this.refRepository = refRepository;
        this.planBlockRepository = planBlockRepository;
        this.weeklyPlanRepository = weeklyPlanRepository;
        this.clock = clock;
    }

    /** 우리 UID 로 돌아온 블록 한 건. 고쳐졌으면 반영하고, 어느 쪽이든 ETag 는 갱신한다. */
    public void reconcileOne(String uid, String title, Instant startAt, Instant endAt,
                             String externalEventId, String resourceHref, String etag) {
        Optional<PlanBlockExternalRef> found = refRepository.findByExternalUid(uid);
        if (found.isEmpty()) {
            return;   // 매핑이 없다 — 지어내지 않는다.
        }
        PlanBlockExternalRef ref = found.get();
        Instant now = clock.now();

        if (ref.neverSent() || !ref.differsFrom(title, startAt, endAt)) {
            ref.recordSent(externalEventId, resourceHref, etag, title, startAt, endAt, now);
            return;   // 우리가 보낸 그대로다 — 우리 쓰기가 돌아온 것이다.
        }

        // 🔴 제목은 되받지 않는다. 태스크 제목은 OpenPlan 안에서 태스크의 이름이고, 그 태스크가
        //    여러 주에 여러 블록으로 배치돼 있을 수 있다. 캘린더에서 한 회차의 제목을 고친 것을
        //    태스크 전체의 이름 변경으로 읽으면 사용자가 의도하지 않은 곳까지 바뀐다.
        //    시각만 되받는다 — 그것이 «일정을 옮겼다» 의 뜻이다.
        moveBlock(ref, startAt, endAt, now);
        ref.recordSent(externalEventId, resourceHref, etag, ref.neverSent() ? title : title, startAt, endAt, now);
    }

    /**
     * 블록을 새 시각으로 옮긴다.
     *
     * <p>🔴 <b>주를 넘는 이동은 블록을 떼어낸다</b>(미배치). 다른 주 계획이 없으면 만들어야 하고,
     * 옮긴 자리가 겹침·가용시간 밖일 수 있다 — 그 판정은 규칙 엔진의 몫이지 동기화가 조용히 할
     * 일이 아니다. 매핑도 함께 정리해 다음 확정이 새 자리에서 다시 잡게 한다.
     */
    private void moveBlock(PlanBlockExternalRef ref, Instant startAt, Instant endAt, Instant now) {
        Optional<WeeklyPlan> plan = weeklyPlanRepository.findById(ref.getWeeklyPlanId());
        if (plan.isEmpty()) {
            return;
        }
        WeeklyPlan weeklyPlan = plan.get();
        Optional<PlanBlock> block = blockOf(ref);
        if (block.isEmpty()) {
            return;   // 그 사이 블록이 사라졌다 — 다음 확정이 매핑을 정리한다.
        }
        weeklyPlan.reopenToDraftIfConfirmed();   // 편집이 일어났다 — 기존 검증 루프에 태운다

        if (sameWeek(weeklyPlan, startAt)) {
            planBlockRepository.reschedule(block.get().getId(), startAt, endAt, weeklyPlan.getId());
            log.info("외부 이동을 되받았다: refId={} planId={}", ref.getId(), weeklyPlan.getId());
        } else {
            planBlockRepository.delete(block.get());
            refRepository.delete(ref);
            log.info("외부 이동이 주를 넘어 블록을 떼어냈다 — 사용자가 새 주에서 다시 배치한다: refId={}",
                    ref.getId());
        }
    }

    /** 매핑의 (주간계획, 태스크, 순번)으로 실제 블록을 찾는다 — plan_block_id 는 불안정하다. */
    private Optional<PlanBlock> blockOf(PlanBlockExternalRef ref) {
        List<PlanBlock> blocks = planBlockRepository.findByWeeklyPlanId(ref.getWeeklyPlanId()).stream()
                .filter(b -> ref.getTaskId().equals(b.getTaskId()))
                .sorted(Comparator.comparing(PlanBlock::getStartAt).thenComparing(PlanBlock::getId))
                .toList();
        return ref.getSequence() < blocks.size() ? Optional.of(blocks.get(ref.getSequence())) : Optional.empty();
    }

    private boolean sameWeek(WeeklyPlan plan, Instant newStart) {
        ZoneId zone = clock.zoneOf(plan.getUserId());
        long offset = ChronoUnit.DAYS.between(plan.getWeekStartDate(),
                LocalDate.ofInstant(newStart, zone));
        return offset >= 0 && offset < 7;
    }

    /**
     * 이번 회차에 안 온 우리 블록 — 외부에서 지워졌다.
     *
     * <p>블록만 지우고 <b>태스크는 미배치로 남긴다</b> — 캘린더에서 일정을 지운 것은 «그 시간에
     * 안 하겠다» 이지 «그 일을 안 하겠다» 가 아니다. 태스크까지 지우면 사용자가 만든 할 일이
     * 사라진다.
     *
     * <p>«없음» 의 원인이 여럿이라 <b>창 안에 있어야 하는데 없을 때만</b> 지운다.
     */
    public void propagateDeletions(UUID userId, Set<String> seenUids, Instant from, Instant to) {
        for (PlanBlockExternalRef ref : refRepository.findByUserId(userId)) {
            if (seenUids.contains(ref.getExternalUid()) || !ref.wasSentWithin(from, to)) {
                continue;
            }
            weeklyPlanRepository.findById(ref.getWeeklyPlanId())
                    .ifPresent(WeeklyPlan::reopenToDraftIfConfirmed);
            blockOf(ref).ifPresent(planBlockRepository::delete);
            refRepository.delete(ref);
            log.info("외부에서 지워진 배치를 정리했다(태스크는 미배치로 남는다): refId={}", ref.getId());
        }
    }
}
