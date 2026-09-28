package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.weeklyplan.domain.PlanBlock;
import com.openplan.backend.weeklyplan.repository.PlanBlockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 확정 시점의 태스크 블록 맞추기 (#69 5단계) — 리뷰가 잡은 경계들.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanBlockOutboundReconcilerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TASK = UUID.randomUUID();
    private static final UUID WEEK_A = UUID.randomUUID();
    private static final UUID WEEK_B = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String WRITE_SCOPE = "openid email https://www.googleapis.com/auth/calendar.events";

    @Mock
    private PlanBlockRepository planBlockRepository;
    @Mock
    private PlanBlockExternalRefRepository refRepository;
    @Mock
    private ExternalCalendarConnectionRepository connectionRepository;
    @Mock
    private OutboundCalendarOpRepository opRepository;
    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private UserClock clock;

    @InjectMocks
    private PlanBlockOutboundReconciler reconciler;

    private ExternalCalendarConnection connection;

    @BeforeEach
    void setUp() {
        given(clock.now()).willReturn(NOW);
        connection = ExternalCalendarConnection.connect(USER, ExternalCalendarProvider.GOOGLE, "me@example.com",
                "enc", "renc", Instant.parse("2030-01-01T00:00:00Z"), WRITE_SCOPE, NOW);
        connection.chooseWriteCalendar("cal-w");
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER)).willReturn(List.of(connection));
        given(jdbc.queryForList(anyString(), eq(String.class), any(UUID.class))).willReturn(List.of("스터디"));
        given(refRepository.save(any())).willAnswer(i -> i.getArgument(0));
        given(planBlockRepository.findByWeeklyPlanId(any())).willReturn(List.of());
        given(refRepository.findByWeeklyPlanId(any())).willReturn(List.of());
        given(refRepository.findByUserIdAndTaskId(USER, TASK)).willReturn(List.of());
    }

    private PlanBlock block(UUID week, String start) {
        Instant startAt = Instant.parse(start);
        return PlanBlock.forTask(week, TASK, startAt, startAt.plusSeconds(3600), NOW);
    }

    private PlanBlockExternalRef sentRef(UUID week, UUID connectionId) {
        PlanBlockExternalRef ref = PlanBlockExternalRef.reserve(USER, connectionId, week, TASK, 0, NOW);
        ref.recordSent("evt-a", null, "etag-a", "스터디",
                Instant.parse("2026-09-21T01:00:00Z"), Instant.parse("2026-09-21T02:00:00Z"), NOW);
        return ref;
    }

    @Test
    @DisplayName("🔴 다른 주로 옮긴 블록은 새로 만들지 않고 원래 매핑을 데려와 수정 한 번으로 보낸다")
    void 주차_이동은_수정_한_번이다() {
        PlanBlockExternalRef movedAway = sentRef(WEEK_A, connection.getId());
        given(refRepository.findByUserIdAndTaskId(USER, TASK)).willReturn(List.of(movedAway));
        // 원래 주(A)에는 이 태스크 블록이 더 없고, 새 주(B)에 하나 생겼다.
        given(planBlockRepository.findByWeeklyPlanId(WEEK_B)).willReturn(List.of(block(WEEK_B, "2026-09-28T01:00:00Z")));

        reconciler.onConfirmed(USER, WEEK_B);

        verify(refRepository, never()).save(any());
        assertThat(movedAway.getWeeklyPlanId()).isEqualTo(WEEK_B);
        ArgumentCaptor<OutboundCalendarOp> captor = ArgumentCaptor.forClass(OutboundCalendarOp.class);
        verify(opRepository).save(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo(OutboundOperation.UPDATE);
        assertThat(captor.getValue().getPayload().uid()).isEqualTo(movedAway.getExternalUid());
    }

    @Test
    @DisplayName("원래 주에 자리가 그대로 있으면 데려오지 않는다 — 그 주의 일정이다")
    void 자리가_남아_있으면_데려오지_않는다() {
        PlanBlockExternalRef stillThere = sentRef(WEEK_A, connection.getId());
        given(refRepository.findByUserIdAndTaskId(USER, TASK)).willReturn(List.of(stillThere));
        given(planBlockRepository.findByWeeklyPlanId(WEEK_A)).willReturn(List.of(block(WEEK_A, "2026-09-21T01:00:00Z")));
        given(planBlockRepository.findByWeeklyPlanId(WEEK_B)).willReturn(List.of(block(WEEK_B, "2026-09-28T01:00:00Z")));

        reconciler.onConfirmed(USER, WEEK_B);

        assertThat(stillThere.getWeeklyPlanId()).isEqualTo(WEEK_A);
        verify(refRepository).save(any());
        ArgumentCaptor<OutboundCalendarOp> captor = ArgumentCaptor.forClass(OutboundCalendarOp.class);
        verify(opRepository).save(captor.capture());
        assertThat(captor.getValue().getOperation()).isEqualTo(OutboundOperation.CREATE);
    }

    @Test
    @DisplayName("🔴 나간 적 없는 자리가 사라지면 대기 CREATE 를 거두고 DELETE 는 쌓지 않는다")
    void 나간_적_없는_자리는_CREATE를_거둔다() {
        PlanBlockExternalRef neverSent = PlanBlockExternalRef.reserve(USER, connection.getId(), WEEK_B, TASK, 0, NOW);
        given(refRepository.findByWeeklyPlanId(WEEK_B)).willReturn(List.of(neverSent));
        List<OutboundCalendarOp> unsent = List.of(OutboundCalendarOp.queue(USER, connection.getId(),
                OutboundTargetType.PLAN_BLOCK, neverSent.getId(), OutboundOperation.CREATE,
                new OutboundPayload(neverSent.getExternalUid(), "스터디", null, null, "cal-w", null, null, null), NOW));
        given(opRepository.findUnsentByTarget(OutboundTargetType.PLAN_BLOCK, neverSent.getId())).willReturn(unsent);

        reconciler.onConfirmed(USER, WEEK_B);

        verify(opRepository).deleteAll(unsent);
        verify(opRepository, never()).save(any());
        verify(refRepository).delete(neverSent);
    }

    @Test
    @DisplayName("🔴 다른 연동으로 나간 매핑은 이 연동이 지우지 않는다")
    void 다른_연동의_매핑은_건드리지_않는다() {
        PlanBlockExternalRef elsewhere = sentRef(WEEK_B, UUID.randomUUID());
        given(refRepository.findByWeeklyPlanId(WEEK_B)).willReturn(List.of(elsewhere));

        reconciler.onConfirmed(USER, WEEK_B);

        verify(opRepository, never()).save(any());
        verify(refRepository, never()).delete(any());
    }
}
