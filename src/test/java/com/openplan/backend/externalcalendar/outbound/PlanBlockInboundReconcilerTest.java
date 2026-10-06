package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.domain.WeeklyPlan;
import com.openplan.backend.weeklyplan.domain.WeeklyPlanStatus;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import com.openplan.backend.weeklyplan.repository.WeeklyPlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 외부에서 고치거나 지운 태스크 블록 되받기 (#69 6단계) — 리뷰가 잡은 경계들.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanBlockInboundReconcilerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    // 2026-09-21(월) 주 — 수요일 KST 14:00 = 05:00Z, 16:00 = 07:00Z
    private static final Instant WED_14 = Instant.parse("2026-09-23T05:00:00Z");
    private static final Instant WED_16 = Instant.parse("2026-09-23T07:00:00Z");
    private static final Instant MON_09 = Instant.parse("2026-09-21T00:00:00Z");

    @Mock
    private PlanBlockExternalRefRepository refRepository;
    @Mock
    private PlanBlockRepository planBlockRepository;
    @Mock
    private WeeklyPlanRepository weeklyPlanRepository;
    @Mock
    private UserClock clock;

    @InjectMocks
    private PlanBlockInboundReconciler reconciler;

    private WeeklyPlan plan;

    @BeforeEach
    void setUp() {
        given(clock.now()).willReturn(NOW);
        given(clock.zoneOf(USER)).willReturn(ZoneId.of("Asia/Seoul"));
        plan = new WeeklyPlan(USER, LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 27), NOW);
        plan.confirm(NOW);
        given(weeklyPlanRepository.findById(plan.getId())).willReturn(Optional.of(plan));
    }

    private PlanBlockExternalRef sentRef(int sequence, Instant startAt) {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, UUID.randomUUID(), plan.getId(), TASK, sequence, NOW);
        ref.recordSent("evt-" + sequence, null, "etag", "스터디", startAt, startAt.plusSeconds(3600), NOW);
        given(refRepository.findByExternalUid(ref.getExternalUid())).willReturn(Optional.of(ref));
        return ref;
    }

    @Test
    @DisplayName("🔴 캘린더에서 제목만 바꾸면 블록을 옮기지 않고 확정도 풀지 않는다")
    void 제목만_바뀌면_확정을_풀지_않는다() {
        PlanBlockExternalRef ref = sentRef(0, WED_14);

        reconciler.reconcileOne(ref.getExternalUid(), "다른 이름", WED_14, WED_14.plusSeconds(3600),
                "evt-0", null, "etag-2");

        assertThat(plan.getStatus()).isEqualTo(WeeklyPlanStatus.CONFIRMED);
        verify(planBlockRepository, never()).reschedule(any(), any(), any(), any());
        assertThat(ref.getEtag()).isEqualTo("etag-2");
    }

    @Test
    @DisplayName("🔴 형제 블록의 순서가 바뀌어도 매핑이 가리키던 블록을 옮긴다 — 순번이 아니라 보낸 시각으로 찾는다")
    void 형제_순서가_바뀌어도_같은_블록을_옮긴다() {
        // A 는 월 09:00 에 순번 0 으로 나갔다가 외부에서 수 16:00 으로 옮겨져 이미 되받았다.
        // B 는 수 14:00 에 순번 1 로 나갔다. 지금 시작 시각 순서는 [B(14:00), A(16:00)] 다.
        PlanBlock a = PlanBlock.forTask(plan.getId(), TASK, WED_16, WED_16.plusSeconds(3600), NOW);
        PlanBlock b = PlanBlock.forTask(plan.getId(), TASK, WED_14, WED_14.plusSeconds(3600), NOW);
        given(planBlockRepository.findByWeeklyPlanId(plan.getId())).willReturn(List.of(a, b));
        PlanBlockExternalRef refB = sentRef(1, WED_14);

        Instant movedTo = WED_14.plusSeconds(1800);
        reconciler.reconcileOne(refB.getExternalUid(), "스터디", movedTo, movedTo.plusSeconds(3600),
                "evt-1", null, "etag-2");

        // 순번 1 로 찾으면 [B, A] 의 1번인 A 가 옮겨진다.
        verify(planBlockRepository).reschedule(b.getId(), movedTo, movedTo.plusSeconds(3600), plan.getId());
        verify(planBlockRepository, never()).reschedule(a.getId(), movedTo, movedTo.plusSeconds(3600), plan.getId());
    }

    @Test
    @DisplayName("보낸 시각에 놓인 블록이 없으면(OpenPlan 에서 옮기고 아직 안 보냈다) 건드리지 않는다")
    void 대상을_모르면_건드리지_않는다() {
        PlanBlock elsewhere = PlanBlock.forTask(plan.getId(), TASK, MON_09, MON_09.plusSeconds(3600), NOW);
        given(planBlockRepository.findByWeeklyPlanId(plan.getId())).willReturn(List.of(elsewhere));
        PlanBlockExternalRef ref = sentRef(0, WED_14);

        reconciler.reconcileOne(ref.getExternalUid(), "스터디", WED_16, WED_16.plusSeconds(3600), "evt-0", null, "e");

        verify(planBlockRepository, never()).reschedule(any(), any(), any(), any());
        assertThat(plan.getStatus()).isEqualTo(WeeklyPlanStatus.CONFIRMED);
        // 🔴 옮기지 않았으니 보낸 시각도 그대로다 — 받은 시각으로 덮어쓰면 매핑이 실제 블록과 어긋난다.
        assertThat(ref.wasSentAt(WED_14, WED_14.plusSeconds(3600))).isTrue();
        assertThat(ref.wasSentAt(WED_16, WED_16.plusSeconds(3600))).isFalse();
        // ETag 는 갱신한다 — 다음 내보내기가 412 로 막히지 않게.
        assertThat(ref.getEtag()).isEqualTo("e");
    }

    @Test
    @DisplayName("🔴 삭제 판정은 이번에 읽은 연동의 매핑만 본다 — 다른 연동으로 나간 블록을 지우지 않는다")
    void 삭제는_연동_경계를_넘지_않는다() {
        UUID connection = UUID.randomUUID();
        PlanBlockExternalRef mine = PlanBlockExternalRef.reserve(USER, connection, plan.getId(), TASK, 0, NOW);
        mine.recordSent("evt", null, "etag", "스터디", WED_14, WED_14.plusSeconds(3600), NOW);
        given(refRepository.findByConnectionId(connection)).willReturn(List.of(mine));

        reconciler.propagateDeletions(connection, Set.of(), Set.of("cal-w"), "cal-w", MON_09, MON_09.plusSeconds(7 * 86400));

        verify(refRepository).delete(mine);
        verify(refRepository, never()).findByUserId(any());
    }
}
