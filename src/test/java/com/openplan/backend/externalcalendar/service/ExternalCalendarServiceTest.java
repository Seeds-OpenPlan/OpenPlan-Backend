package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.externalcalendar.domain.ApplyMode;
import com.openplan.backend.externalcalendar.dto.ApplyEventRequest;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
import com.openplan.backend.common.Weekday;
import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarSelection;
import com.openplan.backend.externalcalendar.provider.CalendarProvider;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
import com.openplan.backend.externalcalendar.provider.ProviderCredential;
import com.openplan.backend.externalcalendar.provider.ProviderEvent;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarEventRepository;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarSelectionRepository;
import com.openplan.backend.externalcalendar.repository.ExternalFixedScheduleRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExternalCalendarService#authorizationUrl} 단위 테스트(DB·Docker 불요).
 *
 * <p><b>회귀 대상.</b> 이 메서드가 {@code providerRegistry.supports(type)}(캘린더 읽기 어댑터
 * 등록 여부)로 걸렀을 때는, 애플도 읽기 어댑터가 있어 통과한 뒤 {@link ExternalCalendarProvider#oauthProvider()}가
 * 던지는 {@link IllegalStateException}이 그대로 올라가 계약된 422(E-COM-009) 대신 미분류 500이 났다.
 * {@link ExternalCalendarProvider#usesOAuth()}로 거르는 지금 구현이 이 케이스를 되돌리면 반드시 실패해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class ExternalCalendarServiceTest {

    @Mock
    private ExternalCalendarConnectionRepository connectionRepository;
    @Mock
    private ExternalCalendarSelectionRepository selectionRepository;
    @Mock
    private ExternalCalendarEventRepository eventRepository;
    @Mock
    private ExternalCalendarEventWriter eventWriter;
    @Mock
    private ExternalFixedScheduleRepository fixedScheduleRepository;
    @Mock
    private CalendarProviderRegistry providerRegistry;
    @Mock
    private ExternalCalendarTokens tokens;
    @Mock
    private ExternalEventToFixedSchedule converter;
    @Mock
    private ExternalCalendarAuthorization authorization;
    @Mock
    private OAuthClient oauthClient;
    @Mock
    private OAuthProperties oauthProperties;
    @Mock
    private UserClock userClock;
    @Mock
    private CalendarProvider calendarProvider;

    @InjectMocks
    private ExternalCalendarService service;

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void authorizationUrl_애플은_읽기_어댑터가_있어도_422로_거절된다() {
        // 프로덕션에서는 AppleCalDavProvider 가 실제로 CalendarProvider 로 등록돼 있어 supports(APPLE)
        // 은 true 다 — 그 상태를 그대로 흉내낸다. lenient 인 이유: 지금(고친) 구현은 usesOAuth() 로만
        // 가르고 이 스텁을 아예 참조하지 않는다 — 참조하지 않는 것 자체가 회귀가 없다는 증거라
        // strict-stub 위반으로 실패시키지 않는다.
        lenient().when(providerRegistry.supports(ExternalCalendarProvider.APPLE)).thenReturn(true);
        // supports() 로 거르던 옛 구현이 여기까지 내려가면 실제 ExternalCalendarAuthorization 이
        // provider.oauthProvider() 를 호출해 IllegalStateException 을 던지는 것과 같은 모양을 흉내낸다.
        lenient().when(authorization.authorizationUrl(eq(ExternalCalendarProvider.APPLE), any()))
                .thenThrow(new IllegalStateException("APPLE 는 CALDAV_BASIC 이라 OAuth 제공자 정의가 없다"));

        // 계약(openapi provider enum=[GOOGLE])대로 422 E-COM-009 여야 한다. 옛 구현으로 되돌리면
        // 위 스텁이 IllegalStateException 을 던져 OpenPlanException 이 아니게 되고, 이 단언이 실패한다.
        assertThatThrownBy(() -> service.authorizationUrl("APPLE", "http://localhost:5173/settings/calendar"))
                .isInstanceOf(OpenPlanException.class)
                .satisfies(ex -> assertThat(((OpenPlanException) ex).errorCode())
                        .isEqualTo(ErrorCode.E_COM_009));

        // 애플은 인가 단계 자체가 없다 — authorization.authorizationUrl(...)까지 내려가면 안 된다.
        // 내려갔다면 oauthProvider()의 IllegalStateException 이 GlobalExceptionHandler 를 타고 500이 된다.
        verifyNoInteractions(authorization);
    }

    @Test
    void authorizationUrl_구글은_authorization으로_위임된다() {
        when(authorization.authorizationUrl(ExternalCalendarProvider.GOOGLE, "http://localhost:5173/cb"))
                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=x");

        String url = service.authorizationUrl("GOOGLE", "http://localhost:5173/cb");

        assertThat(url).isEqualTo("https://accounts.google.com/o/oauth2/v2/auth?state=x");
    }

    @Test
    @DisplayName("두 캘린더가 같은 원본 일정을 주면 한 번만 저장한다 — 같은 호출 안에서 UQ 를 스스로 위반했다")
    void listEvents_같은_호출_안의_중복은_한_건으로_접힌다() {
        ExternalCalendarConnection connection = stubSync();
        // 초대받은 일정이 개인·팀 캘린더에 함께 보이는 흔한 경우 — 두 selection 이 같은 id 를 준다.
        ProviderEvent shared = new ProviderEvent("evt-1", "팀 회의",
                Instant.parse("2026-08-20T01:00:00Z"), Instant.parse("2026-08-20T02:00:00Z"), "내 캘린더");
        when(calendarProvider.listEvents(any(), eq("cal-a"), any(), any(), any())).thenReturn(List.of(shared));
        when(calendarProvider.listEvents(any(), eq("cal-b"), any(), any(), any())).thenReturn(List.of(shared));

        service.listEvents(USER, connection.getId(), null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExternalCalendarEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventWriter).insertAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("동기화가 겹쳐 UQ 를 위반해도 근거 불명의 500 을 내지 않는다")
    void listEvents_동기화_경합은_500_이_아니다() {
        ExternalCalendarConnection connection = stubSync();
        when(calendarProvider.listEvents(any(), eq("cal-a"), any(), any(), any())).thenReturn(List.of(
                new ProviderEvent("evt-a", "가", Instant.parse("2026-08-20T01:00:00Z"),
                        Instant.parse("2026-08-20T02:00:00Z"), "내 캘린더")));
        when(calendarProvider.listEvents(any(), eq("cal-b"), any(), any(), any())).thenReturn(List.of(
                new ProviderEvent("evt-b", "나", Instant.parse("2026-08-20T03:00:00Z"),
                        Instant.parse("2026-08-20T04:00:00Z"), "내 캘린더")));
        // 탭 두 개·중복 새로고침으로 같은 connection 동기화가 겹친 상황 — 진 쪽이 UQ 를 맞는다.
        doThrow(new DataIntegrityViolationException("uq_external_event")).when(eventWriter).insertAll(any());
        // 배치가 통째로 되돌아갔으니 한 건씩 다시 넣는다 — 겹친 것만 다시 실패한다.
        doThrow(new DataIntegrityViolationException("uq_external_event")).when(eventWriter).insertOne(any());

        assertThatCode(() -> service.listEvents(USER, connection.getId(), null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이미 반영한 일정을 다시 반영하면 409 — 재시도·이중 클릭에 고정 일정이 두 벌 생기면 안 된다")
    void apply_이미_처리한_일정은_409() {
        ExternalCalendarConnection connection = ExternalCalendarConnection.connect(
                USER, ExternalCalendarProvider.GOOGLE, "me@example.com", "enc", "renc", NOW, NOW);
        ExternalCalendarEvent event = ExternalCalendarEvent.candidate(connection.getId(),
                "evt-1", "팀 회의", Instant.parse("2026-08-20T01:00:00Z"),
                Instant.parse("2026-08-20T02:00:00Z"), "내 캘린더", NOW);
        event.apply(ApplyMode.AS_IS);   // 첫 번째 호출이 이미 끝난 상태

        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(connectionRepository.findByIdAndUserId(connection.getId(), USER))
                .thenReturn(Optional.of(connection));

        assertThatThrownBy(() -> service.apply(USER, event.getId(), new ApplyEventRequest("AS_IS", null)))
                .isInstanceOf(OpenPlanException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.E_EXT_005);

        // 두 번째 호출은 고정 일정을 만들지 않는다 — 이게 이 검사의 목적이다.
        verifyNoInteractions(converter);
    }

    @Test
    @DisplayName("🔴 신규 저장은 바깥 트랜잭션 밖으로 나간다 — 여기서 넣으면 경합을 잡아도 500 이 된다")
    void 신규_저장은_별도_트랜잭션으로_나간다() {
        ExternalCalendarConnection connection = stubSync();
        when(calendarProvider.listEvents(any(), eq("cal-a"), any(), any(), any())).thenReturn(List.of(
                new ProviderEvent("evt-a", "가", Instant.parse("2026-08-20T01:00:00Z"),
                        Instant.parse("2026-08-20T02:00:00Z"), "내 캘린더")));
        when(calendarProvider.listEvents(any(), eq("cal-b"), any(), any(), any())).thenReturn(List.of());

        service.listEvents(USER, connection.getId(), null);

        // 동기화는 조회 트랜잭션 안에서 돈다. 그 트랜잭션에서 직접 insert 하면 UQ 위반을 잡아도
        // 되돌릴 수 없다 — 실패한 flush 가 세션을 못 쓰게 만들고, 참여 트랜잭션 실패가 바깥을
        // rollback-only 로 표시해 커밋에서 다시 터진다(2026-08-27 통합 테스트로 재현).
        // 그래서 저장은 REQUIRES_NEW 인 writer 로만 나가야 한다.
        verify(eventWriter).insertAll(any());
        verify(eventRepository, never()).saveAll(any());
        verify(eventRepository, never()).saveAllAndFlush(any());
    }

    /** 동기화 경로가 도는 데 필요한 최소 스텁. 반환값은 활성 연결. */
    // ── #68 원격 수정·삭제 전파 ────────────────────────────────────────────────

    /** 이번 회차에 제공자가 이 일정들을 돌려주도록 세운다(두 캘린더 모두 같은 목록). */
    private void providerReturns(ProviderEvent... events) {
        when(calendarProvider.listEvents(any(), eq("cal-a"), any(), any(), any())).thenReturn(List.of(events));
        when(calendarProvider.listEvents(any(), eq("cal-b"), any(), any(), any())).thenReturn(List.of());
    }

    /**
     * 이미 저장돼 있는(그리고 반영까지 된) 일정 하나.
     *
     * <p>{@code externalCalendarId} 를 표시 이름과 <b>따로</b> 받는 것이 핵심이다 — 삭제 귀속은
     * 이름이 아니라 식별자로 한다(2026-08-29 리뷰 Blocking).
     */
    private ExternalCalendarEvent storedApplied(UUID connectionId, ApplyMode mode,
                                                String sourceCalendar, String externalCalendarId) {
        ExternalCalendarEvent e = ExternalCalendarEvent.candidate(connectionId, "evt-1", "옛 제목",
                Instant.parse("2026-08-20T01:00:00Z"), Instant.parse("2026-08-20T02:00:00Z"), sourceCalendar, NOW);
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        e.locateIn(externalCalendarId);
        e.apply(mode);
        return e;
    }

    @Test
    @DisplayName("원격에서 시간이 바뀌면 AS_IS 로 반영한 고정 일정이 따라 바뀐다")
    void 원격_수정이_고정일정에_반영된다() {
        ExternalCalendarConnection connection = stubSync();
        ExternalCalendarEvent stored = storedApplied(connection.getId(), ApplyMode.AS_IS, "개인", "cal-a");
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of(stored));
        providerReturns(new ProviderEvent("evt-1", "새 제목",
                Instant.parse("2026-08-20T04:00:00Z"), Instant.parse("2026-08-20T05:00:00Z"), "개인"));

        FixedSchedule linked = FixedSchedule.createExternal(USER, connection.getId(), stored.getId(),
                "옛 제목", Weekday.THU, LocalTime.of(10, 0), LocalTime.of(11, 0),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20), NOW);
        when(fixedScheduleRepository.findByExternalCalendarEventId(stored.getId())).thenReturn(Optional.of(linked));
        FixedSchedule converted = FixedSchedule.createExternal(USER, connection.getId(), stored.getId(),
                "새 제목", Weekday.THU, LocalTime.of(13, 0), LocalTime.of(14, 0),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20), NOW);
        when(converter.convert(eq(USER), eq(stored), isNull())).thenReturn(converted);

        service.listEvents(USER, connection.getId(), null);

        assertThat(linked.getTitle()).isEqualTo("새 제목");
        assertThat(linked.getStartTime()).isEqualTo(LocalTime.of(13, 0));
    }

    @Test
    @DisplayName("EDITED 로 손수정한 고정 일정은 원격이 바뀌어도 건드리지 않는다 — 사용자가 한 일을 지운다")
    void 손수정한_고정일정은_원격_수정이_덮지_않는다() {
        ExternalCalendarConnection connection = stubSync();
        ExternalCalendarEvent stored = storedApplied(connection.getId(), ApplyMode.EDITED, "개인", "cal-a");
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of(stored));
        providerReturns(new ProviderEvent("evt-1", "새 제목",
                Instant.parse("2026-08-20T04:00:00Z"), Instant.parse("2026-08-20T05:00:00Z"), "개인"));

        service.listEvents(USER, connection.getId(), null);

        verify(fixedScheduleRepository, never()).findByExternalCalendarEventId(any());
        verify(converter, never()).convert(any(), any(), any());
    }

    @Test
    @DisplayName("원격에서 사라지면 고정 일정까지 지운다")
    void 원격_삭제가_고정일정까지_지운다() {
        ExternalCalendarConnection connection = stubSync();
        ExternalCalendarEvent stored = storedApplied(connection.getId(), ApplyMode.AS_IS, "개인", "cal-a");
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of(stored));
        providerReturns();   // 아무것도 안 돌려준다 = 원격에서 지워졌다

        service.listEvents(USER, connection.getId(), null);

        verify(fixedScheduleRepository).deleteByExternalCalendarEventIdIn(List.of(stored.getId()));
        verify(eventRepository).deleteAll(List.of(stored));
    }

    @Test
    @DisplayName("🔴 동기화 창 밖의 일정은 안 왔어도 지우지 않는다 — 조회조차 안 한 구간이다")
    void 창_밖의_일정은_지우지_않는다() {
        ExternalCalendarConnection connection = stubSync();
        // today=2026-08-20 기준 창은 과거 7일~미래 56일. 1년 전 일정은 애초에 조회 대상이 아니다.
        ExternalCalendarEvent old = ExternalCalendarEvent.candidate(connection.getId(), "evt-old", "작년 회의",
                Instant.parse("2025-08-20T01:00:00Z"), Instant.parse("2025-08-20T02:00:00Z"), "개인", NOW);
        ReflectionTestUtils.setField(old, "id", UUID.randomUUID());
        old.apply(ApplyMode.AS_IS);
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of(old));
        providerReturns();

        service.listEvents(USER, connection.getId(), null);

        verify(fixedScheduleRepository, never()).deleteByExternalCalendarEventIdIn(any());
        verify(eventRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("🔴 선택 해제한 캘린더의 일정은 안 왔어도 지우지 않는다 — 그 캘린더는 조회하지 않았다")
    void 선택_해제한_캘린더의_일정은_지우지_않는다() {
        ExternalCalendarConnection connection = stubSync();   // 선택된 캘린더는 "개인"·"팀"
        ExternalCalendarEvent stored = storedApplied(connection.getId(), ApplyMode.AS_IS, "예전에 골랐던 캘린더", "cal-gone");
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of(stored));
        providerReturns();

        service.listEvents(USER, connection.getId(), null);

        verify(fixedScheduleRepository, never()).deleteByExternalCalendarEventIdIn(any());
        verify(eventRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("🔴 이름이 같아도 다른 캘린더면 지우지 않는다 — 이름은 유일하지 않다")
    void 이름이_같아도_다른_캘린더면_지우지_않는다() {
        ExternalCalendarConnection connection = stubSync();   // 선택: cal-a("개인") · cal-b("팀")
        // 선택 해제된 캘린더인데 **이름이 선택된 것과 같다.** 이름으로 판정하면 지워진다.
        ExternalCalendarEvent stored = storedApplied(connection.getId(), ApplyMode.AS_IS, "개인", "cal-c");
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of(stored));
        providerReturns();

        service.listEvents(USER, connection.getId(), null);

        verify(fixedScheduleRepository, never()).deleteByExternalCalendarEventIdIn(any());
        verify(eventRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("🔴 출처 캘린더를 모르는 일정은 지우지 않는다 — 모르면 남긴다")
    void 출처를_모르는_일정은_지우지_않는다() {
        ExternalCalendarConnection connection = stubSync();
        ExternalCalendarEvent stored = storedApplied(connection.getId(), ApplyMode.AS_IS, "개인", null);
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of(stored));
        providerReturns();

        service.listEvents(USER, connection.getId(), null);

        verify(fixedScheduleRepository, never()).deleteByExternalCalendarEventIdIn(any());
        verify(eventRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("🔴 진짜 동시 반영은 500 이 아니라 409 다 — isCandidate() 검사는 check-then-act 라 둘 다 통과한다")
    void 동시_반영은_409다() {
        ExternalCalendarConnection connection = ExternalCalendarConnection.connect(
                USER, ExternalCalendarProvider.GOOGLE, "me@example.com", "enc", "renc", NOW, NOW);
        ExternalCalendarEvent event = ExternalCalendarEvent.candidate(connection.getId(), "evt-1", "회의",
                Instant.parse("2026-08-20T01:00:00Z"), Instant.parse("2026-08-20T02:00:00Z"), "개인", NOW);
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
        when(connectionRepository.findByIdAndUserId(connection.getId(), USER)).thenReturn(Optional.of(connection));
        FixedSchedule fs = FixedSchedule.createExternal(USER, connection.getId(), event.getId(), "회의",
                Weekday.THU, LocalTime.of(10, 0), LocalTime.of(11, 0),
                LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 20), NOW);
        when(converter.convert(eq(USER), eq(event), isNull())).thenReturn(fs);
        // 진 쪽이 겪는 것 — ux_fixed_external_event 위반이 flush 에서 터진다.
        when(fixedScheduleRepository.saveAndFlush(fs)).thenThrow(new DataIntegrityViolationException("ux_fixed_external_event"));

        assertThatThrownBy(() -> service.apply(USER, event.getId(), new ApplyEventRequest("AS_IS", null)))
                .isInstanceOf(OpenPlanException.class)
                .satisfies(ex -> assertThat(((OpenPlanException) ex).errorCode())
                        .as("계약이 이 자리에 409 E-EXT-005 를 정본으로 명시한다")
                        .isEqualTo(ErrorCode.E_EXT_005));
    }

    private ExternalCalendarConnection stubSync() {
        ExternalCalendarConnection connection = ExternalCalendarConnection.connect(
                USER, ExternalCalendarProvider.GOOGLE, "me@example.com", "enc", "renc", NOW, NOW);
        when(connectionRepository.findByIdAndUserId(connection.getId(), USER)).thenReturn(Optional.of(connection));
        when(selectionRepository.findByConnectionIdOrderByCalendarNameAsc(connection.getId())).thenReturn(List.of(
                ExternalCalendarSelection.select(connection.getId(), "cal-a", "개인", NOW),
                ExternalCalendarSelection.select(connection.getId(), "cal-b", "팀", NOW)));
        when(tokens.usableCredential(connection)).thenReturn(ProviderCredential.bearer("token"));
        when(userClock.zoneOf(USER)).thenReturn(ZoneId.of("Asia/Seoul"));
        when(userClock.todayOf(USER)).thenReturn(LocalDate.of(2026, 8, 20));
        when(userClock.now()).thenReturn(NOW);
        when(providerRegistry.get(ExternalCalendarProvider.GOOGLE)).thenReturn(calendarProvider);
        when(eventRepository.findByConnectionId(connection.getId())).thenReturn(List.of());
        when(eventRepository.findByConnectionIdOrderByStartAtAsc(connection.getId())).thenReturn(List.of());
        return connection;
    }
}
