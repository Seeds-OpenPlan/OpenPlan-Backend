package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.global.time.UserClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 고정 일정 회차 맞추기 (#69 4단계) — 리뷰가 잡은 경계들.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FixedOccurrenceReconcilerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final String WRITE_SCOPE = "openid email https://www.googleapis.com/auth/calendar.events";

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private FixedOccurrenceRepository occurrenceRepository;
    @Mock
    private OutboundCalendarOpRepository opRepository;
    @Mock
    private UserClock clock;
    @Mock
    private FixedOccurrenceWriter occurrenceWriter;

    @InjectMocks
    private FixedOccurrenceReconciler reconciler;

    private ExternalCalendarConnection connection;
    private FixedOccurrencePlanner.Pattern pattern;

    @BeforeEach
    void setUp() {
        given(clock.now()).willReturn(NOW);
        given(clock.zoneOf(USER)).willReturn(ZONE);
        connection = ExternalCalendarConnection.connect(USER, ExternalCalendarProvider.GOOGLE, "me@example.com",
                "enc", "renc", Instant.parse("2030-01-01T00:00:00Z"), WRITE_SCOPE, NOW);
        connection.chooseWriteCalendar("cal-w");
        pattern = new FixedOccurrencePlanner.Pattern(UUID.randomUUID(), "수업", DayOfWeek.WEDNESDAY,
                LocalTime.of(9, 0), LocalTime.of(10, 0), null, null);
        given(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<FixedOccurrencePlanner.Pattern>>any(), eq(USER)))
                .willReturn(List.of(pattern));
        given(jdbc.queryForList(anyString(), eq(LocalDate.class), any(UUID.class))).willReturn(List.of());
        given(jdbc.queryForList(anyString(), eq(String.class), eq(USER))).willReturn(List.of());
        given(occurrenceRepository.findByUserId(USER)).willReturn(List.of());
        given(occurrenceWriter.reserve(any())).willAnswer(i -> i.getArgument(0));
    }

    private List<FixedOccurrencePlanner.Occurrence> wanted() {
        LocalDate today = NOW.atZone(ZONE).toLocalDate();
        return FixedOccurrencePlanner.expand(pattern, today, ZONE, Set.of(), DayOfWeek.MONDAY);
    }

    private OutboundCalendarOp pendingCreate(FixedOccurrence o) {
        return OutboundCalendarOp.queue(USER, connection.getId(), OutboundTargetType.FIXED_OCCURRENCE, o.getId(),
                OutboundOperation.CREATE,
                new OutboundPayload(o.getExternalUid(), "옛 제목", null, null, "cal-w", null, null, null), NOW);
    }

    @Test
    @DisplayName("🔴 회차 생성 경합은 조회를 깨지 않는다 — 진 쪽은 별도 트랜잭션에서 실패하고 물러난다")
    void 회차_생성_경합은_삼킨다() {
        given(occurrenceWriter.reserve(any())).willThrow(new DataIntegrityViolationException("ux_fixed_occurrence"));

        assertThatCode(() -> reconciler.reconcile(USER, connection)).doesNotThrowAnyException();

        verify(occurrenceWriter, org.mockito.Mockito.atLeastOnce()).reserve(any());
        verify(occurrenceRepository, never()).saveAndFlush(any());
        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 아직 안 나간 CREATE 가 있으면 동기화마다 또 쌓지 않는다")
    void 대기_CREATE가_있으면_또_쌓지_않는다() {
        List<FixedOccurrencePlanner.Occurrence> wanted = wanted();
        List<FixedOccurrence> stored = wanted.stream()
                .map(w -> FixedOccurrence.reserve(pattern.fixedScheduleId(), USER, connection.getId(), w.date(), NOW))
                .toList();
        given(occurrenceRepository.findByUserId(USER)).willReturn(stored);
        FixedOccurrence first = stored.getFirst();
        OutboundCalendarOp pending = pendingCreate(first);
        given(opRepository.findUnsentByTarget(OutboundTargetType.FIXED_OCCURRENCE, first.getId()))
                .willReturn(List.of(pending));

        reconciler.reconcile(USER, connection);

        verify(opRepository, never()).save(ArgumentMatchers.argThat(op -> op.getTargetId().equals(first.getId())));
        assertThat(pending.getOperation()).isEqualTo(OutboundOperation.CREATE);
        assertThat(pending.getPayload().title()).isEqualTo("수업");
    }

    @Test
    @DisplayName("🔴 나간 적 없는 회차가 빠지면 대기 CREATE 를 거두고 DELETE 는 쌓지 않는다")
    void 나간_적_없는_회차는_CREATE를_거둔다() {
        given(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<FixedOccurrencePlanner.Pattern>>any(), eq(USER)))
                .willReturn(List.of());
        FixedOccurrence gone = FixedOccurrence.reserve(pattern.fixedScheduleId(), USER, connection.getId(),
                LocalDate.of(2026, 9, 23), NOW);
        given(occurrenceRepository.findByUserId(USER)).willReturn(List.of(gone));
        List<OutboundCalendarOp> unsent = List.of(pendingCreate(gone));
        given(opRepository.findUnsentByTarget(OutboundTargetType.FIXED_OCCURRENCE, gone.getId())).willReturn(unsent);

        reconciler.reconcile(USER, connection);

        verify(opRepository).deleteAll(unsent);
        verify(opRepository, never()).save(any());
        verify(occurrenceRepository).delete(gone);
    }

    @Test
    @DisplayName("🔴 다른 연동으로 나간 회차는 이 연동이 고치거나 지우지 않는다")
    void 다른_연동의_회차는_건드리지_않는다() {
        given(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<FixedOccurrencePlanner.Pattern>>any(), eq(USER)))
                .willReturn(List.of());
        FixedOccurrence elsewhere = FixedOccurrence.reserve(pattern.fixedScheduleId(), USER, UUID.randomUUID(),
                LocalDate.of(2026, 9, 23), NOW);
        elsewhere.recordSent("evt-1", null, "etag-1", "수업", NOW, NOW, NOW);
        given(occurrenceRepository.findByUserId(USER)).willReturn(List.of(elsewhere));

        reconciler.reconcile(USER, connection);

        verify(opRepository, never()).save(any());
        verify(occurrenceRepository, never()).delete(any());
    }
}
