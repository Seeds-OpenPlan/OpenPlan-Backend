package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.provider.CalendarProvider;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
import com.openplan.backend.externalcalendar.provider.ProviderWriteResult;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.externalcalendar.service.ExternalCalendarTokens;
import com.openplan.backend.global.time.UserClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 대상 캘린더를 어느 값에서 읽는지에 대한 단위 테스트 (#69 · #85 리뷰 Should-fix).
 *
 * <p><b>회귀 대상.</b> payload 의 {@code writeCalendarId} 는 적재 시점 스냅샷이다. CREATE 가
 * 아직 안 나간 동안 사용자가 대상을 바꾸면(setWriteCalendar), 스냅샷을 그대로 쓰는 구현은
 * <b>사용자가 이미 버린 캘린더에 새 일정을 만든다</b> — setWriteCalendar 가 목록 대조로 막으려던
 * «사용자가 모르는 곳에 일정이 생긴다» 가 재시도 경로에서 되살아난다.
 *
 * <p>반대로 UPDATE·DELETE 는 스냅샷이 정답이다: 그 이벤트는 그때의 그 캘린더 안에 실재하므로
 * 지금 설정을 따르면 없는 곳에서 고치려 드는 셈이 된다.
 */
@ExtendWith(MockitoExtension.class)
class OutboundOpExecutorTest {

    @Mock
    private OutboundCalendarOpRepository opRepository;
    @Mock
    private ScheduleExternalRefRepository refRepository;
    @Mock
    private FixedOccurrenceRepository occurrenceRepository;
    @Mock
    private PlanBlockExternalRefRepository blockRefRepository;
    @Mock
    private ExternalCalendarConnectionRepository connectionRepository;
    @Mock
    private CalendarProviderRegistry registry;
    @Mock
    private CalendarProvider provider;
    @Mock
    private ExternalCalendarTokens tokens;
    @Mock
    private UserClock clock;

    @InjectMocks
    private OutboundOpExecutor executor;

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

    /** 쓰기 스코프를 받은 활성 구글 연동. {@code currentTarget} 이 «지금 설정된» 대상이다. */
    private ExternalCalendarConnection connection(String currentTarget) {
        ExternalCalendarConnection c = ExternalCalendarConnection.connect(USER,
                ExternalCalendarProvider.GOOGLE, "me@example.com", "enc", "renc", NOW,
                "https://www.googleapis.com/auth/calendar.events", NOW);
        c.chooseWriteCalendar(currentTarget);
        return c;
    }

    private OutboundCalendarOp op(OutboundOperation operation, String snapshotCalendarId) {
        OutboundPayload payload = new OutboundPayload("uid-1", "팀 회의",
                Instant.parse("2026-09-28T01:00:00Z"), Instant.parse("2026-09-28T02:00:00Z"),
                snapshotCalendarId, "evt-1", "/dav/evt-1.ics", "etag-1");
        return OutboundCalendarOp.queue(USER, UUID.randomUUID(), OutboundTargetType.SCHEDULE,
                UUID.randomUUID(), operation, payload, NOW);
    }

    private void stubExecutable(OutboundCalendarOp op, ExternalCalendarConnection conn, boolean needsProvider) {
        when(opRepository.findById(op.getId())).thenReturn(Optional.of(op));
        when(clock.now()).thenReturn(NOW);
        when(connectionRepository.findById(op.getConnectionId())).thenReturn(Optional.of(conn));
        if (needsProvider) {
            when(registry.get(ExternalCalendarProvider.GOOGLE)).thenReturn(provider);
        }
    }

    @Test
    @DisplayName("🔴 CREATE 는 적재 시점 스냅샷이 아니라 지금 설정된 대상으로 나간다")
    void create_는_지금_설정된_대상으로_나간다() {
        OutboundCalendarOp op = op(OutboundOperation.CREATE, "cal-옛것");
        stubExecutable(op, connection("cal-지금것"), true);
        when(provider.createEvent(any(), eq("cal-지금것"), any()))
                .thenReturn(new ProviderWriteResult("evt-new", "/dav/new.ics", "etag-new"));

        executor.execute(op.getId());

        // 스냅샷(cal-옛것)으로 나가면 사용자가 버린 캘린더에 일정이 생긴다.
        verify(provider).createEvent(any(), eq("cal-지금것"), any());
        verify(provider, never()).createEvent(any(), eq("cal-옛것"), any());
        assertThat(op.getStatus()).isEqualTo(OutboundStatus.DONE);
    }

    @Test
    @DisplayName("UPDATE 는 스냅샷을 쓴다 — 그 이벤트는 그때의 그 캘린더 안에 있다")
    void update_는_스냅샷_대상을_쓴다() {
        OutboundCalendarOp op = op(OutboundOperation.UPDATE, "cal-옛것");
        stubExecutable(op, connection("cal-지금것"), true);
        when(provider.updateEvent(any(), eq("cal-옛것"), any(), any()))
                .thenReturn(new ProviderWriteResult("evt-1", "/dav/evt-1.ics", "etag-2"));

        executor.execute(op.getId());

        verify(provider).updateEvent(any(), eq("cal-옛것"), any(), any());
        verify(provider, never()).updateEvent(any(), eq("cal-지금것"), any(), any());
    }

    @Test
    @DisplayName("대상이 해제된 뒤의 CREATE 는 제공자를 부르지 않고 실패로 남는다(FAILED 는 다시 집힌다)")
    void create_대상이_해제되면_보내지_않는다() {
        OutboundCalendarOp op = op(OutboundOperation.CREATE, "cal-옛것");
        stubExecutable(op, connection(null), false);

        executor.execute(op.getId());

        verifyNoInteractions(provider);
        assertThat(op.getStatus()).isEqualTo(OutboundStatus.FAILED);
        assertThat(op.getLastError()).contains("대상 캘린더가 없다");
    }
    @Test
    @DisplayName("🔴 대상이 해제되면 해제 전에 쌓인 UPDATE·DELETE 도 보내지 않는다 — 해제 이후 변경은 더 나가지 않는다")
    void 대상이_해제되면_update_delete_도_보내지_않는다() {
        for (OutboundOperation operation : List.of(OutboundOperation.UPDATE, OutboundOperation.DELETE)) {
            OutboundCalendarOp op = op(operation, "cal-A");
            stubExecutable(op, connection(null), false);

            executor.execute(op.getId());

            assertThat(op.getStatus()).as(operation.name()).isEqualTo(OutboundStatus.FAILED);
            assertThat(op.getLastError()).as(operation.name()).contains("내보내기가 해제된 상태다");
        }
        verifyNoInteractions(provider);
    }
}
