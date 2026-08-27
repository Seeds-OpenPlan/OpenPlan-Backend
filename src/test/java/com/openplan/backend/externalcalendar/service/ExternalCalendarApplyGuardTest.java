package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.externalcalendar.domain.ApplyMode;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.dto.ApplyEventRequest;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 반영 재적용 가드 (ONB-09).
 *
 * <p><b>고정하는 것</b>: 이미 반영·제외한 후보 일정에 다시 반영을 요청하면 고정 일정이 두 벌
 * 생기지 않고 409 로 돌아온다는 것. 재시도·이중 클릭이 원래 지목된 시나리오다.
 *
 * <p><b>왜 E-EXT-004 가 아닌가</b>: 그쪽 메시지는 연동(계정) 중복용인 "이미 연결된 외부
 * 계정입니다" 다. 일정 재반영에 그 문구가 나가면 사용자는 자기가 하지도 않은 계정 연결을
 * 의심한다 — 같은 409 라도 무엇이 중복인지가 다르면 사용자가 할 일도 다르다.
 *
 * <p>⚠️ <b>이 가드는 check-then-act 다.</b> 순차 재시도는 막지만 진짜 동시 요청은 막지 못한다
 * ({@code ExternalCalendarEvent} 에 {@code @Version} 이 없고 {@code fixed_schedules} 에 원본
 * 이벤트를 향한 UQ 도 없다). 그 방어는 마이그레이션이 필요해 별도 작업으로 남겼다.
 */
@ExtendWith(MockitoExtension.class)
class ExternalCalendarApplyGuardTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Mock private ExternalCalendarConnectionRepository connectionRepository;
    @Mock private ExternalCalendarSelectionRepository selectionRepository;
    @Mock private ExternalCalendarEventRepository eventRepository;
    @Mock private ExternalFixedScheduleRepository fixedScheduleRepository;
    @Mock private CalendarProviderRegistry providerRegistry;
    @Mock private ExternalCalendarTokens tokens;
    @Mock private ExternalEventToFixedSchedule converter;
    @Mock private ExternalCalendarAuthorization authorization;
    @Mock private OAuthClient oauthClient;
    @Mock private OAuthProperties oauthProperties;
    @Mock private UserClock userClock;

    @InjectMocks private ExternalCalendarService service;

    @Test
    @DisplayName("이미 반영한 일정을 다시 반영하면 409 E-EXT-005 — 고정 일정이 두 벌 생기면 안 된다")
    void 재적용은_409() {
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
}
