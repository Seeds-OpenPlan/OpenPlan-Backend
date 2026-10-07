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

    // ── #85 리뷰 Blocking · 대상 캘린더를 바꾼 뒤 ───────────────────────────────

    @Test
    @DisplayName("🔴 이미 내보낸 일정의 UPDATE 는 대상을 바꿔도 «있는 곳» 으로 나간다")
    void 이미_내보낸_일정은_있는_곳으로_나간다() {
        // 캘린더 A 로 내보낸 뒤 사용자가 대상을 B 로 바꾼 상태
        // 같은 연동에서 대상만 A→B 로 바꿨다 — 매핑은 그 연동을 가리킨다.
        ExternalCalendarConnection home = connection(WRITE_SCOPE, "cal-B", ConnectionStatus.ACTIVE);
        ScheduleExternalRef sent = ScheduleExternalRef.reserve(schedule.getId(), home.getId(), "uid-1", NOW);
        sent.recordSent("evt-1", "/dav/evt-1.ics", "etag-1", "스터디",
                schedule.getStartAt(), schedule.getEndAt(), NOW);
        sent.locateInCalendar("cal-A");
        given(refRepository.findById(schedule.getId())).willReturn(Optional.of(sent));
        given(connectionRepository.findById(home.getId())).willReturn(Optional.of(home));
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER)).willReturn(List.of(home));

        queue.enqueueScheduleUpsert(USER, schedule);

        ArgumentCaptor<OutboundCalendarOp> captor = ArgumentCaptor.forClass(OutboundCalendarOp.class);
        verify(opRepository).save(captor.capture());
        // B 로 보내면 이벤트가 없어 404 가 영구히 반복된다.
        assertThat(captor.getValue().getPayload().writeCalendarId()).isEqualTo("cal-A");
        assertThat(captor.getValue().getOperation()).isEqualTo(OutboundOperation.UPDATE);
    }

    @Test
    @DisplayName("🔴 삭제도 «있는 곳» 으로 — 매핑 행은 곧 CASCADE 로 사라져 나중엔 알 길이 없다")
    void 삭제도_있는_곳으로_나간다() {
        // 같은 연동에서 대상만 A→B 로 바꿨다 — 매핑은 그 연동을 가리킨다.
        ExternalCalendarConnection home = connection(WRITE_SCOPE, "cal-B", ConnectionStatus.ACTIVE);
        ScheduleExternalRef sent = ScheduleExternalRef.reserve(schedule.getId(), home.getId(), "uid-1", NOW);
        sent.recordSent("evt-1", "/dav/evt-1.ics", "etag-1", "스터디",
                schedule.getStartAt(), schedule.getEndAt(), NOW);
        sent.locateInCalendar("cal-A");
        given(refRepository.findById(schedule.getId())).willReturn(Optional.of(sent));
        given(connectionRepository.findById(home.getId())).willReturn(Optional.of(home));
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER)).willReturn(List.of(home));

        queue.enqueueScheduleDelete(USER, schedule.getId());

        ArgumentCaptor<OutboundCalendarOp> captor = ArgumentCaptor.forClass(OutboundCalendarOp.class);
        verify(opRepository).save(captor.capture());
        assertThat(captor.getValue().getPayload().writeCalendarId()).isEqualTo("cal-A");
    }

    @Test
    @DisplayName("아직 안 내보낸 것(sentCalendarId=null)은 지금 설정된 대상으로 나간다")
    void 아직_안_내보낸_것은_지금_대상으로_나간다() {
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER))
                .willReturn(List.of(connection(WRITE_SCOPE, "cal-B", ConnectionStatus.ACTIVE)));

        queue.enqueueScheduleUpsert(USER, schedule);

        ArgumentCaptor<OutboundCalendarOp> captor = ArgumentCaptor.forClass(OutboundCalendarOp.class);
        verify(opRepository).save(captor.capture());
        assertThat(captor.getValue().getPayload().writeCalendarId()).isEqualTo("cal-B");
        assertThat(captor.getValue().getOperation()).isEqualTo(OutboundOperation.CREATE);
    }

    // ---------- #79·#80 리뷰 Blocking — 이미 매핑이 있는 일정 ----------

    private ScheduleExternalRef mappedTo(ExternalCalendarConnection home, boolean sent) {
        ScheduleExternalRef ref = ScheduleExternalRef.reserve(schedule.getId(), home.getId(),
                OpenPlanEventUid.forSchedule(schedule.getId()), NOW);
        if (sent) {
            ref.recordSent("evt-1", null, "etag-1", "스터디", schedule.getStartAt(), schedule.getEndAt(), NOW);
        }
        given(refRepository.findById(schedule.getId())).willReturn(Optional.of(ref));
        given(connectionRepository.findById(home.getId())).willReturn(Optional.of(home));
        return ref;
    }

    private OutboundCalendarOp savedOp() {
        ArgumentCaptor<OutboundCalendarOp> captor = ArgumentCaptor.forClass(OutboundCalendarOp.class);
        verify(opRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("🔴 이미 내보낸 일정은 원래 연동·캘린더로 고친다 — 지금 뽑히는 연동이 바뀌어도 갈아타지 않는다")
    void 이미_내보낸_일정은_원래_연동으로_보낸다() {
        ExternalCalendarConnection home = connection(WRITE_SCOPE, "home-cal", ConnectionStatus.ACTIVE);
        ExternalCalendarConnection other = connection(WRITE_SCOPE, "other-cal", ConnectionStatus.ACTIVE);
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER)).willReturn(List.of(other, home));
        mappedTo(home, true);

        queue.enqueueScheduleUpsert(USER, schedule);

        OutboundCalendarOp op = savedOp();
        assertThat(op.getOperation()).isEqualTo(OutboundOperation.UPDATE);
        assertThat(op.getConnectionId()).isEqualTo(home.getId());
        assertThat(op.getPayload().writeCalendarId()).isEqualTo("home-cal");
    }

    @Test
    @DisplayName("🔴 원래 연동의 쓰기 권한이 사라졌으면 다른 연동으로 보내지 않는다")
    void 원래_연동을_쓸_수_없으면_적지_않는다() {
        ExternalCalendarConnection home = connection(null, "home-cal", ConnectionStatus.ACTIVE);
        ExternalCalendarConnection other = connection(WRITE_SCOPE, "other-cal", ConnectionStatus.ACTIVE);
        given(connectionRepository.findByUserIdOrderByConnectedAtAsc(USER)).willReturn(List.of(other));
        mappedTo(home, true);

        queue.enqueueScheduleUpsert(USER, schedule);
        queue.enqueueScheduleDelete(USER, schedule.getId());

        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 CREATE 가 대기 중이면 UPDATE 를 따로 쌓지 않고 그 CREATE 의 내용을 고친다")
    void 대기_중인_CREATE에_합친다() {
        ExternalCalendarConnection home = connection(WRITE_SCOPE, "home-cal", ConnectionStatus.ACTIVE);
        mappedTo(home, false);
        OutboundCalendarOp pendingCreate = OutboundCalendarOp.queue(USER, home.getId(), OutboundTargetType.SCHEDULE,
                schedule.getId(), OutboundOperation.CREATE,
                new OutboundPayload("uid", "옛 제목", null, null, "home-cal", null, null, null), NOW);
        given(opRepository.findUnsentByTarget(OutboundTargetType.SCHEDULE, schedule.getId()))
                .willReturn(List.of(pendingCreate));

        queue.enqueueScheduleUpsert(USER, schedule);

        verify(opRepository, never()).save(any());
        assertThat(pendingCreate.getOperation()).isEqualTo(OutboundOperation.CREATE);
        assertThat(pendingCreate.getPayload().title()).isEqualTo("스터디");
    }

    @Test
    @DisplayName("🔴 밖에 나간 적 없는 일정을 지우면 대기 중인 CREATE 를 거두고 DELETE 는 쌓지 않는다")
    void 나간_적_없으면_CREATE를_거둔다() {
        ExternalCalendarConnection home = connection(WRITE_SCOPE, "home-cal", ConnectionStatus.ACTIVE);
        mappedTo(home, false);
        List<OutboundCalendarOp> unsent = List.of(OutboundCalendarOp.queue(USER, home.getId(),
                OutboundTargetType.SCHEDULE, schedule.getId(), OutboundOperation.CREATE,
                new OutboundPayload("uid", "스터디", null, null, "home-cal", null, null, null), NOW));
        given(opRepository.findUnsentByTarget(OutboundTargetType.SCHEDULE, schedule.getId())).willReturn(unsent);

        queue.enqueueScheduleDelete(USER, schedule.getId());

        verify(opRepository).deleteAll(unsent);
        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("밖에 나간 일정을 지우면 대기 UPDATE 를 거두고 원래 연동으로 DELETE 를 적는다")
    void 나간_일정의_삭제는_원래_연동으로() {
        ExternalCalendarConnection home = connection(WRITE_SCOPE, "home-cal", ConnectionStatus.ACTIVE);
        mappedTo(home, true);
        List<OutboundCalendarOp> unsent = List.of(OutboundCalendarOp.queue(USER, home.getId(),
                OutboundTargetType.SCHEDULE, schedule.getId(), OutboundOperation.UPDATE,
                new OutboundPayload("uid", "스터디", null, null, "home-cal", "evt-1", null, "etag-1"), NOW));
        given(opRepository.findUnsentByTarget(OutboundTargetType.SCHEDULE, schedule.getId())).willReturn(unsent);

        queue.enqueueScheduleDelete(USER, schedule.getId());

        verify(opRepository).deleteAll(unsent);
        OutboundCalendarOp op = savedOp();
        assertThat(op.getOperation()).isEqualTo(OutboundOperation.DELETE);
        assertThat(op.getConnectionId()).isEqualTo(home.getId());
        assertThat(op.getPayload().writeCalendarId()).isEqualTo("home-cal");
        assertThat(op.getPayload().externalEventId()).isEqualTo("evt-1");
    }

    @Test
    @DisplayName("🔴 내보내기 대상을 비우면 이미 나간 일정의 수정도 더 내보내지 않는다 — 해제는 «내보내지 않음» 이다")
    void 대상을_비우면_나간_일정의_수정도_내보내지_않는다() {
        ExternalCalendarConnection home = connection(WRITE_SCOPE, null, ConnectionStatus.ACTIVE);
        ScheduleExternalRef ref = mappedTo(home, true);
        ref.locateInCalendar("cal-A");

        queue.enqueueScheduleUpsert(USER, schedule);

        verify(opRepository, never()).save(any());
    }

    @Test
    @DisplayName("🔴 내보내기 대상을 비운 뒤 일정을 지우면 밖은 그대로 두고, 대기 작업만 거둔다")
    void 대상을_비운_뒤_삭제는_대기_작업만_거둔다() {
        ExternalCalendarConnection home = connection(WRITE_SCOPE, null, ConnectionStatus.ACTIVE);
        ScheduleExternalRef ref = mappedTo(home, true);
        ref.locateInCalendar("cal-A");
        List<OutboundCalendarOp> unsent = List.of(OutboundCalendarOp.queue(USER, home.getId(),
                OutboundTargetType.SCHEDULE, schedule.getId(), OutboundOperation.UPDATE,
                new OutboundPayload("uid", "스터디", null, null, "cal-A", "evt-1", null, "etag-1"), NOW));
        given(opRepository.findUnsentByTarget(OutboundTargetType.SCHEDULE, schedule.getId())).willReturn(unsent);

        queue.enqueueScheduleDelete(USER, schedule.getId());

        verify(opRepository).deleteAll(unsent);
        verify(opRepository, never()).save(any());
    }
}
