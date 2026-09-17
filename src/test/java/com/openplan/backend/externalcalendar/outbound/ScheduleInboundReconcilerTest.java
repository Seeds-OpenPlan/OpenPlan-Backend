package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.schedule.domain.Schedule;
import com.openplan.backend.schedule.repository.ScheduleRepository;
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
 * 외부에서 고쳐지거나 지워진 우리 일정을 되받는다 (#69 D4).
 *
 * <p>🔴 여기서도 <b>안 하는 경우</b>가 더 중요하다 — 잘못 되받으면 사용자 일정이 사라지거나
 * 같은 편집이 무한히 다시 적용된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScheduleInboundReconcilerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID CONNECTION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final Instant FROM = Instant.parse("2026-09-14T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-09T00:00:00Z");
    private static final Instant START = Instant.parse("2026-09-22T01:00:00Z");
    private static final Instant END = Instant.parse("2026-09-22T02:00:00Z");

    @Mock
    private ScheduleExternalRefRepository refRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private PlanBlockRepository planBlockRepository;
    @Mock
    private WeeklyPlanRepository weeklyPlanRepository;
    @Mock
    private UserClock clock;

    @InjectMocks
    private ScheduleInboundReconciler reconciler;

    private Schedule schedule;
    private ScheduleExternalRef ref;

    @BeforeEach
    void setUp() {
        given(clock.now()).willReturn(NOW);
        schedule = new Schedule(USER, "스터디", 60, 2, START, END, null, NOW);
        ref = ScheduleExternalRef.reserve(schedule.getId(), CONNECTION,
                OpenPlanEventUid.forSchedule(schedule.getId()), NOW);
        ref.recordSent("evt-1", null, "\"e1\"", "스터디", START, END, NOW);
        given(refRepository.findByExternalUid(ref.getExternalUid())).willReturn(Optional.of(ref));
        given(scheduleRepository.findById(schedule.getId())).willReturn(Optional.of(schedule));
        given(planBlockRepository.findByScheduleId(any())).willReturn(Optional.empty());
    }

    @Test
    @DisplayName("🔴 우리가 보낸 그대로면 되받지 않는다 — 우리 쓰기가 돌아온 것이지 사용자의 편집이 아니다")
    void 보낸_그대로면_되받지_않는다() {
        reconciler.reconcileOne(USER, ref.getExternalUid(), "스터디", START, END, "evt-1", null, "\"e1\"");

        assertThat(schedule.getTitle()).isEqualTo("스터디");
        assertThat(schedule.getStartAt()).isEqualTo(START);
    }

    @Test
    @DisplayName("외부에서 제목을 고치면 되받는다")
    void 외부_편집을_되받는다() {
        reconciler.reconcileOne(USER, ref.getExternalUid(), "스터디 (장소 변경)", START, END, "evt-1", null, "\"e2\"");

        assertThat(schedule.getTitle()).isEqualTo("스터디 (장소 변경)");
    }

    @Test
    @DisplayName("🔴 되받은 뒤 스냅샷을 새 값으로 갱신한다 — 안 하면 같은 편집을 무한히 다시 적용한다")
    void 되받은_뒤_스냅샷을_갱신한다() {
        reconciler.reconcileOne(USER, ref.getExternalUid(), "바뀐 제목", START, END, "evt-1", null, "\"e2\"");

        // 두 번째 회차에 같은 값이 다시 와도 «고쳐졌다» 로 읽히면 안 된다.
        assertThat(ref.changedOutside("바뀐 제목", START, END)).isFalse();
    }

    @Test
    @DisplayName("🔴 매핑이 없는 우리 UID 는 새로 만들지 않는다 — 지어내면 사용자가 지운 일정이 되살아난다")
    void 매핑이_없으면_만들지_않는다() {
        given(refRepository.findByExternalUid("openplan-schedule-x@openplan.services")).willReturn(Optional.empty());

        reconciler.reconcileOne(USER, "openplan-schedule-x@openplan.services", "무엇", START, END, "e", null, "\"x\"");

        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("이번 회차에 안 왔고 창 안이면 외부에서 지워진 것이다")
    void 창_안에서_사라지면_지운다() {
        given(refRepository.findByConnectionId(CONNECTION)).willReturn(List.of(ref));

        reconciler.propagateDeletions(CONNECTION, Set.of(), FROM, TO);

        verify(scheduleRepository).delete(schedule);
    }

    @Test
    @DisplayName("🔴 창 밖의 일정은 «안 왔다» 고 지우지 않는다 — 원래 안 보인다")
    void 창_밖은_지우지_않는다() {
        given(refRepository.findByConnectionId(CONNECTION)).willReturn(List.of(ref));

        // 동기화 창을 일정보다 뒤로 옮긴다 — 그 일정은 원래 이번 조회에 오지 않는다.
        reconciler.propagateDeletions(CONNECTION, Set.of(),
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));

        verify(scheduleRepository, never()).delete(any());
    }

    @Test
    @DisplayName("이번 회차에 온 일정은 지우지 않는다")
    void 온_것은_지우지_않는다() {
        given(refRepository.findByConnectionId(CONNECTION)).willReturn(List.of(ref));

        reconciler.propagateDeletions(CONNECTION, Set.of(ref.getExternalUid()), FROM, TO);

        verify(scheduleRepository, never()).delete(any());
    }

    @Test
    @DisplayName("🔴 아직 안 보낸 일정은 지우지 않는다 — 보내지도 않은 것을 «외부에 없다» 고 지우면 데이터가 사라진다")
    void 안_보낸_것은_지우지_않는다() {
        ScheduleExternalRef notSent = ScheduleExternalRef.reserve(UUID.randomUUID(), CONNECTION,
                "openplan-schedule-y@openplan.services", NOW);
        given(refRepository.findByConnectionId(CONNECTION)).willReturn(List.of(notSent));

        reconciler.propagateDeletions(CONNECTION, Set.of(), FROM, TO);

        verify(scheduleRepository, never()).delete(any());
    }
}
