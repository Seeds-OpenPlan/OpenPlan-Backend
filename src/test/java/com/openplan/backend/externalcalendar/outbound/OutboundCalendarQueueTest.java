package com.openplan.backend.externalcalendar.outbound;

import com.openplan.backend.externalcalendar.domain.ConnectionStatus;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.global.time.UserClock;
import com.openplan.backend.schedule.domain.Schedule;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 밖으로 내보낼 것을 적는 자리 (#69 3단계).
 *
 * <p>🔴 여기서 <b>안 적는 경우</b>가 더 중요하다. 잘못 적으면 사용자가 모르는 캘린더에 일정이
 * 생기거나, 권한 없는 연동으로 403 만 쌓인다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboundCalendarQueueTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final String WRITE_SCOPE = "openid email https://www.googleapis.com/auth/calendar.events";

    @Mock
    private OutboundCalendarOpRepository opRepository;
    @Mock
    private ScheduleExternalRefRepository refRepository;
    @Mock
    private ExternalCalendarConnectionRepository connectionRepository;
    @Mock
    private UserClock clock;

    @InjectMocks
    private OutboundCalendarQueue queue;

    private Schedule schedule;

    @BeforeEach
    void setUp() {
        given(clock.now()).willReturn(NOW);
        schedule = new Schedule(USER, "스터디", 60, 2,
                Instant.parse("2026-09-22T01:00:00Z"), Instant.parse("2026-09-22T02:00:00Z"), null, NOW);
        given(refRepository.findById(any())).willReturn(Optional.empty());
        given(refRepository.save(any())).willAnswer(i -> i.getArgument(0));
    }

    private ExternalCalendarConnection connection(String grantedScope, String writeCalendarId, ConnectionStatus status) {
        ExternalCalendarConnection c = ExternalCalendarConnection.connect(USER, ExternalCalendarProvider.GOOGLE,
                "me@example.com", "enc", "renc", Instant.parse("2030-01-01T00:00:00Z"), grantedScope, NOW);
        c.chooseWriteCalendar(writeCalendarId);
        c.changeStatus(status);
        return c;
    }

    @Test
    @DisplayName("연동이 없으면 적지 않는다 — 내보낼 곳이 없다")
    void 연동이_없으면_적지_않는다() {
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER)).willReturn(List.of());

        queue.enqueueScheduleUpsert(USER, schedule);

        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 쓰기 권한이 없으면 적지 않는다 — 스코프를 넓히기 전에 연동한 사용자")
    void 쓰기_권한이_없으면_적지_않는다() {
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER))
                .willReturn(List.of(connection(null, "cal-1", ConnectionStatus.ACTIVE)));

        queue.enqueueScheduleUpsert(USER, schedule);

        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 대상 캘린더를 안 골랐으면 적지 않는다 — 아무 데나 고르면 모르는 곳에 일정이 생긴다")
    void 대상_캘린더가_없으면_적지_않는다() {
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER))
                .willReturn(List.of(connection(WRITE_SCOPE, null, ConnectionStatus.ACTIVE)));

        queue.enqueueScheduleUpsert(USER, schedule);

        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("비활성 연동에는 적지 않는다")
    void 비활성이면_적지_않는다() {
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER))
                .willReturn(List.of(connection(WRITE_SCOPE, "cal-1", ConnectionStatus.INACTIVE)));

        queue.enqueueScheduleUpsert(USER, schedule);

        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("조건이 갖춰지면 CREATE 로 적고, 대상 캘린더를 payload 에 박는다")
    void 조건이_갖춰지면_적는다() {
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER))
                .willReturn(List.of(connection(WRITE_SCOPE, "cal-1", ConnectionStatus.ACTIVE)));

        queue.enqueueScheduleUpsert(USER, schedule);

        ArgumentCaptor<OutboundCalendarOp> captor = ArgumentCaptor.forClass(OutboundCalendarOp.class);
        verify(opRepository).save(captor.capture());
        OutboundCalendarOp op = captor.getValue();

        assertThat(op.getOperation()).isEqualTo(OutboundOperation.CREATE);
        assertThat(op.getTargetType()).isEqualTo(OutboundTargetType.SCHEDULE);
        assertThat(op.getStatus()).isEqualTo(OutboundStatus.PENDING);
        // 적재 시점의 설정을 박아 둔다 — 나중에 바뀌어도 이미 밖에 있는 일정은 원래 캘린더에서 고쳐야 한다.
        assertThat(op.getPayload().writeCalendarId()).isEqualTo("cal-1");
        // 🔴 UID 가 실려야 다음 동기화가 이것을 «우리 것» 으로 알아본다.
        assertThat(OpenPlanEventUid.isOurs(op.getPayload().uid())).isTrue();
    }

    @Test
    @DisplayName("🔴 내보낸 적 없는 일정의 삭제는 적지 않는다 — 밖에 지울 것이 없다")
    void 매핑이_없으면_삭제를_적지_않는다() {
        given(refRepository.findById(any())).willReturn(Optional.empty());

        queue.enqueueScheduleDelete(USER, schedule.getId());

        verify(opRepository, never()).save(any());
    }
}
