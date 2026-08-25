package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarEventRepository;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarSelectionRepository;
import com.openplan.backend.externalcalendar.repository.ExternalFixedScheduleRepository;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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

    @InjectMocks
    private ExternalCalendarService service;

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
}
