package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
        verify(eventRepository).saveAll(captor.capture());
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
        doThrow(new DataIntegrityViolationException("uq_external_event")).when(eventRepository).saveAll(any());

        assertThatCode(() -> service.listEvents(USER, connection.getId(), null)).doesNotThrowAnyException();
    }

    /** 동기화 경로가 도는 데 필요한 최소 스텁. 반환값은 활성 연결. */
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
