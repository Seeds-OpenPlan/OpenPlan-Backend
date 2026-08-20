package com.openplan.backend.externalcalendar.service;

import com.openplan.backend.auth.oauth.OAuthClient;
import com.openplan.backend.auth.oauth.OAuthException;
import com.openplan.backend.auth.oauth.OAuthProperties;
import com.openplan.backend.auth.oauth.OAuthTokenSet;
import com.openplan.backend.auth.oauth.OAuthUserInfo;
import com.openplan.backend.externalcalendar.domain.ApplyMode;
import com.openplan.backend.externalcalendar.domain.ApplyStatus;
import com.openplan.backend.externalcalendar.domain.ConnectionStatus;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarConnection;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarEvent;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import com.openplan.backend.externalcalendar.domain.ExternalCalendarSelection;
import com.openplan.backend.externalcalendar.dto.ApplyEventRequest;
import com.openplan.backend.externalcalendar.dto.ApplyEventResponse;
import com.openplan.backend.externalcalendar.dto.CreateConnectionRequest;
import com.openplan.backend.externalcalendar.dto.ExternalConnectionResponse;
import com.openplan.backend.externalcalendar.dto.ExternalEventResponse;
import com.openplan.backend.externalcalendar.dto.ProviderCalendarResponse;
import com.openplan.backend.externalcalendar.dto.SaveSelectionsRequest;
import com.openplan.backend.externalcalendar.dto.UpdateConnectionRequest;
import com.openplan.backend.externalcalendar.provider.CalendarProviderRegistry;
import com.openplan.backend.externalcalendar.provider.ProviderCredential;
import com.openplan.backend.externalcalendar.provider.ProviderCalendar;
import com.openplan.backend.externalcalendar.provider.ProviderEvent;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarConnectionRepository;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarEventRepository;
import com.openplan.backend.externalcalendar.repository.ExternalCalendarSelectionRepository;
import com.openplan.backend.externalcalendar.repository.ExternalFixedScheduleRepository;
import com.openplan.backend.fixedschedule.domain.FixedSchedule;
import com.openplan.backend.fixedschedule.domain.FixedScheduleStatus;
import com.openplan.backend.global.error.ErrorCode;
import com.openplan.backend.global.error.OpenPlanException;
import com.openplan.backend.global.time.UserClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 외부 캘린더 연동 (ST-B1-11 · ONB-07/08/09 · FIX-13~17).
 *
 * <p><b>동기화 시점</b>: 별도 배치가 없다(ADR-0006 신규 스케줄러 금지). {@code GET .../events} 호출이
 * 곧 동기화이며, openapi 요약도 "선택 캘린더 기준 동기화 수행 후 반환"으로 그렇게 적혀 있다.
 *
 * <p><b>소유자 격리</b>: 전 경로가 user_id 스코프로 조회한다. 부재와 타인 소유를 같은 404 로 돌려
 * 존재 여부가 새지 않게 한다(NFR-030).
 */
@Service
public class ExternalCalendarService {

    private static final Logger log = LoggerFactory.getLogger(ExternalCalendarService.class);

    /** 동기화 구간 — 지난 주(회고 화면이 지난 일정을 참조한다)부터 8주 뒤까지. */
    private static final int SYNC_PAST_DAYS = 7;
    private static final int SYNC_FUTURE_DAYS = 56;

    private final ExternalCalendarConnectionRepository connectionRepository;
    private final ExternalCalendarSelectionRepository selectionRepository;
    private final ExternalCalendarEventRepository eventRepository;
    private final ExternalFixedScheduleRepository fixedScheduleRepository;
    private final CalendarProviderRegistry providerRegistry;
    private final ExternalCalendarTokens tokens;
    private final ExternalEventToFixedSchedule converter;
    private final ExternalCalendarAuthorization authorization;
    private final OAuthClient oauthClient;
    private final OAuthProperties oauthProperties;
    private final UserClock userClock;

    public ExternalCalendarService(ExternalCalendarConnectionRepository connectionRepository,
                                   ExternalCalendarSelectionRepository selectionRepository,
                                   ExternalCalendarEventRepository eventRepository,
                                   ExternalFixedScheduleRepository fixedScheduleRepository,
                                   CalendarProviderRegistry providerRegistry,
                                   ExternalCalendarTokens tokens,
                                   ExternalEventToFixedSchedule converter,
                                   ExternalCalendarAuthorization authorization,
                                   OAuthClient oauthClient,
                                   OAuthProperties oauthProperties,
                                   UserClock userClock) {
        this.connectionRepository = connectionRepository;
        this.selectionRepository = selectionRepository;
        this.eventRepository = eventRepository;
        this.fixedScheduleRepository = fixedScheduleRepository;
        this.providerRegistry = providerRegistry;
        this.tokens = tokens;
        this.converter = converter;
        this.authorization = authorization;
        this.oauthClient = oauthClient;
        this.oauthProperties = oauthProperties;
        this.userClock = userClock;
    }

    /** 캘린더 연동 인가 시작 (ONB-07 · FIX-14) — 제공자 인가 페이지 주소를 돌려준다. */
    public String authorizationUrl(String provider, String redirectUri) {
        ExternalCalendarProvider type = parseProvider(provider);
        if (!providerRegistry.supports(type)) {
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("provider", type.name()));
        }
        return authorization.authorizationUrl(type, redirectUri);
    }

    /** 연동 목록·상태 (FIX-13). */
    @Transactional(readOnly = true)
    public List<ExternalConnectionResponse> list(UUID userId) {
        return connectionRepository.findByUserIdOrderByConnectedAtAsc(userId).stream()
                .map(connection -> ExternalConnectionResponse.of(connection,
                        selectionRepository.findByConnectionIdOrderByCalendarNameAsc(connection.getId())))
                .toList();
    }

    /**
     * 연동 추가 (ONB-07 · FIX-14) — 인가 코드를 토큰으로 바꾸고 계정을 확인해 저장한다.
     *
     * <p>중복은 두 겹으로 막는다: 저장 전 조회와 UQ(user_id, provider, account_identifier).
     * 동시 요청은 앞의 조회를 함께 통과할 수 있어 <b>제약이 최종 판정</b>이며, 그것을 409 로 옮긴다.
     */
    @Transactional
    public ExternalConnectionResponse create(UUID userId, CreateConnectionRequest request) {
        ExternalCalendarProvider provider = parseProvider(request.provider());
        requireCredentialShape(provider, request);
        if (!providerRegistry.supports(provider)) {
            // 계약이 여는 제공자와 구현이 있는 제공자가 다를 수 있다 — 조용히 성공시키지 않는다.
            throw new OpenPlanException(ErrorCode.E_EXT_001, Map.of("provider", provider.name()));
        }
        if (!tokens.isCipherConfigured()) {
            // 설정 누락을 제공자 탓으로 돌리지 않도록 로그를 분명히 남긴다.
            log.error("EXT_TOKEN_KEY 미설정 — 외부 캘린더 연동을 저장할 수 없다");
            throw new OpenPlanException(ErrorCode.E_COM_005);
        }
        NewConnectionSecret secret = provider.usesOAuth()
                ? exchangeOAuth(provider, request)
                : verifyCalDav(provider, request);

        if (connectionRepository.existsByUserIdAndProviderAndAccountIdentifier(
                userId, provider, secret.accountIdentifier())) {
            throw new OpenPlanException(ErrorCode.E_EXT_004);
        }

        ExternalCalendarConnection connection = ExternalCalendarConnection.connect(
                userId, provider, secret.accountIdentifier(),
                secret.accessTokenEnc(), secret.refreshTokenEnc(), secret.tokenExpiresAt(),
                userClock.now());
        try {
            connectionRepository.saveAndFlush(connection);
        } catch (DataIntegrityViolationException e) {
            throw new OpenPlanException(ErrorCode.E_EXT_004);
        }
        return ExternalConnectionResponse.of(connection, List.of());
    }

    /**
     * 저장 직전의 자격증명 — 제공자별 갈래가 여기서 하나로 합쳐진다.
     *
     * <p>네이버는 {@code refreshTokenEnc}·{@code tokenExpiresAt} 이 <b>둘 다 null</b> 이다. 애플리케이션
     * 비밀번호는 만료되지 않고 갱신 개념도 없기 때문이며, 만료를 모르는 값으로 두면
     * {@code ExternalCalendarTokens} 의 만료 판정이 그대로 통과시킨다 — 스키마를 바꿀 필요가 없었다.
     */
    private record NewConnectionSecret(String accountIdentifier, String accessTokenEnc,
                                       String refreshTokenEnc, Instant tokenExpiresAt) {
    }

    /** 구글·카카오 — 인가 코드를 토큰으로 바꾸고 계정을 확인한다. */
    private NewConnectionSecret exchangeOAuth(ExternalCalendarProvider provider, CreateConnectionRequest request) {
        // 코드를 쓰기 전에 확인한다 — 남의 인가 코드를 내 세션에 붙이는 경로를 먼저 끊는다.
        authorization.verifyState(provider, request.state());

        OAuthProperties.Client client = oauthProperties.client(provider.oauthProvider())
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_EXT_001, Map.of("provider", provider.name())));

        try {
            OAuthTokenSet tokenSet = oauthClient.exchangeCodeForTokens(provider.oauthProvider(), client,
                    request.authCode(), request.redirectUri());
            OAuthUserInfo userInfo = oauthClient.fetchUserInfo(provider.oauthProvider(), tokenSet.accessToken());
            String accountIdentifier = userInfo.email() != null ? userInfo.email() : userInfo.providerUserId();
            return new NewConnectionSecret(accountIdentifier,
                    tokens.encrypt(tokenSet.accessToken()),
                    tokens.encrypt(tokenSet.refreshToken()),
                    tokens.expiresAt(tokenSet.expiresInSeconds()));
        } catch (OAuthException e) {
            log.warn("외부 캘린더 연결 실패: provider={}", provider, e);
            throw new OpenPlanException(ErrorCode.E_EXT_001, Map.of("provider", provider.name()));
        }
    }

    /**
     * 네이버 — 인가 단계가 없으므로 <b>실제로 붙여 봐서</b> 자격증명을 확인한다.
     *
     * <p>확인 없이 저장하면 "연동됨"으로 보이는 연결이 <b>조회 때마다 실패</b>한다. 사용자는 캘린더를
     * 연결했다고 믿고 그 시간을 비워 두지 않는다. 붙을 수 있음을 증명한 것만 저장한다.
     *
     * <p>아이디·비밀번호가 틀리면 어댑터가 {@code E-EXT-002}(422)로 올린다 — 사용자가 고칠 수 있는
     * 오류라 제공자 장애(502)와 화면이 달라야 한다.
     */
    private NewConnectionSecret verifyCalDav(ExternalCalendarProvider provider, CreateConnectionRequest request) {
        String naverId = request.naverId().trim();
        providerRegistry.get(provider)
                .listCalendars(ProviderCredential.basic(naverId, request.appPassword()));
        return new NewConnectionSecret(naverId, tokens.encrypt(request.appPassword()), null, null);
    }

    /**
     * 제공자에 맞는 자격증명 조합이 실렸는지 확인한다 — {@code CreateConnectionRequest} 에서 옮겨온 검증.
     *
     * <p>빈 검증 애너테이션은 "이 필드는 항상 필요하다"만 말할 수 있는데, 계약은 제공자에 따라 필수
     * 필드가 갈린다({@code oneOf}). DTO 에 {@code @NotBlank} 를 남겨 두면 네이버 요청이 authCode
     * 없음으로 먼저 튕겨 <b>계약이 허용한 모양을 서버가 거부</b>한다. 그래서 검증을 없앤 게 아니라
     * 어느 조합인지 아는 이 지점으로 옮겼다.
     *
     * <p>오류 코드·상세 모양을 {@code @Valid} 실패와 <b>같게</b> 맞춘다(E-COM-001 + {@code fields}).
     * 검증 위치가 바뀌었다고 FE 가 보는 응답까지 바뀌면, 옮긴 것이 아니라 계약을 바꾼 것이 된다.
     */
    private void requireCredentialShape(ExternalCalendarProvider provider, CreateConnectionRequest request) {
        List<Map<String, Object>> missing = new ArrayList<>();
        if (provider.usesOAuth()) {
            requireText(missing, "authCode", request.authCode());
            requireText(missing, "redirectUri", request.redirectUri());
            requireText(missing, "state", request.state());
        } else {
            requireText(missing, "naverId", request.naverId());
            requireText(missing, "appPassword", request.appPassword());
        }
        if (!missing.isEmpty()) {
            throw new OpenPlanException(ErrorCode.E_COM_001, Map.of("fields", missing));
        }
    }

    private static void requireText(List<Map<String, Object>> missing, String field, String value) {
        if (value == null || value.isBlank()) {
            missing.add(Map.of("field", field, "rule", "NotBlank", "message", "필수 값입니다"));
        }
    }

    /**
     * 연동 활성/비활성 (FIX-16) — 유래 고정 일정 상태를 <b>같은 트랜잭션에서</b> 미러한다(AC3).
     *
     * <p>미러를 나중에 하거나 별도 트랜잭션으로 두면, 연동은 꺼졌는데 그 시간이 여전히 배치 불가로
     * 남는 창이 생긴다.
     */
    @Transactional
    public ExternalConnectionResponse updateStatus(UUID userId, UUID connectionId, UpdateConnectionRequest request) {
        ExternalCalendarConnection connection = requireConnection(userId, connectionId);
        ConnectionStatus status;
        try {
            status = ConnectionStatus.fromApiValue(request.status());
        } catch (IllegalArgumentException e) {
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("status", String.valueOf(request.status())));
        }
        connection.changeStatus(status);

        FixedScheduleStatus mirrored = (status == ConnectionStatus.ACTIVE)
                ? FixedScheduleStatus.ACTIVE
                : FixedScheduleStatus.INACTIVE;
        int changed = fixedScheduleRepository.updateStatusByConnectionId(connectionId, mirrored);
        log.debug("연동 상태 미러 반영: connectionId={} status={} fixedSchedules={}", connectionId, status, changed);

        return ExternalConnectionResponse.of(connection,
                selectionRepository.findByConnectionIdOrderByCalendarNameAsc(connectionId));
    }

    /**
     * 연동 해제 (FIX-17) — 유래 고정 일정·선택·후보 일정이 FK CASCADE 로 함께 사라진다(AC4).
     * 그래서 이후 주간 계획에 반영되지 않는다.
     */
    @Transactional
    public void delete(UUID userId, UUID connectionId) {
        connectionRepository.delete(requireConnection(userId, connectionId));
    }

    /** 제공자 캘린더 목록 (ONB-08) — 이미 고른 것은 {@code selected=true}로 표시한다. */
    @Transactional
    public List<ProviderCalendarResponse> listCalendars(UUID userId, UUID connectionId) {
        ExternalCalendarConnection connection = requireConnection(userId, connectionId);
        List<ProviderCalendar> calendars = providerRegistry.get(connection.getProvider())
                .listCalendars(tokens.usableCredential(connection));

        List<String> selectedIds = selectionRepository.findByConnectionIdOrderByCalendarNameAsc(connectionId).stream()
                .map(ExternalCalendarSelection::getExternalCalendarId)
                .toList();

        return calendars.stream()
                .map(calendar -> new ProviderCalendarResponse(
                        calendar.externalCalendarId(), calendar.name(),
                        selectedIds.contains(calendar.externalCalendarId())))
                .toList();
    }

    /**
     * 가져올 캘린더 선택 저장 (ONB-08 · FIX-15) — <b>전체 교체</b>다.
     * 목록에서 빠진 캘린더는 선택 해제가 아니라 삭제되며, 그 캘린더에서 온 후보 일정은 다음
     * 동기화에서 갱신되지 않는다.
     */
    @Transactional
    public void saveSelections(UUID userId, UUID connectionId, SaveSelectionsRequest request) {
        requireConnection(userId, connectionId);

        selectionRepository.deleteByConnectionId(connectionId);
        selectionRepository.flush();   // 같은 (connection_id, external_calendar_id) 재삽입이 UQ 에 걸리지 않도록

        Instant now = userClock.now();
        List<ExternalCalendarSelection> selections = request.selections().stream()
                .map(selection -> ExternalCalendarSelection.select(
                        connectionId, selection.externalCalendarId(), selection.name(), now))
                .toList();
        selectionRepository.saveAll(selections);
    }

    /**
     * 후보 일정 목록 (ONB-08/09) — 선택 캘린더 기준으로 동기화한 뒤 반환한다.
     *
     * <p>비활성 연동은 동기화하지 않는다(FIX-16). 이미 가져온 것은 그대로 보여 준다 —
     * 껐다고 기록이 사라지면 다시 켰을 때 사용자의 판단(APPLIED·EXCLUDED)이 함께 없어진다.
     */
    @Transactional
    public List<ExternalEventResponse> listEvents(UUID userId, UUID connectionId, ApplyStatus applyStatus) {
        ExternalCalendarConnection connection = requireConnection(userId, connectionId);
        if (connection.isActive()) {
            synchronize(userId, connection);
        }
        List<ExternalCalendarEvent> events = (applyStatus == null)
                ? eventRepository.findByConnectionIdOrderByStartAtAsc(connectionId)
                : eventRepository.findByConnectionIdAndApplyStatusOrderByStartAtAsc(connectionId, applyStatus);

        return events.stream().map(ExternalEventResponse::of).toList();
    }

    /**
     * 반영 방식 적용 (ONB-09 · AC2) — AS_IS·EDITED 는 고정 일정(source=EXTERNAL)을 만들고,
     * EXCLUDE 는 만들지 않고 제외로 기록한다. 생성분은 이후 계획의 배치 제약이 된다(V2 입력).
     */
    @Transactional
    public ApplyEventResponse apply(UUID userId, UUID eventId, ApplyEventRequest request) {
        ApplyMode mode = parseMode(request.mode());
        ExternalCalendarEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
        // 소유자 확인은 연결을 거쳐서 한다 — 후보 일정 자체에는 user_id 가 없다.
        requireConnection(userId, event.getConnectionId());

        UUID fixedScheduleId = null;
        if (mode != ApplyMode.EXCLUDE) {
            FixedSchedule fixedSchedule = converter.convert(userId, event,
                    mode == ApplyMode.EDITED ? request.edited() : null);
            fixedScheduleRepository.save(fixedSchedule);
            fixedScheduleId = fixedSchedule.getId();
        }
        event.apply(mode);

        return new ApplyEventResponse(event.getId(), event.getApplyStatus().name(), fixedScheduleId);
    }

    /**
     * 선택 캘린더의 일정을 가져와 후보를 갱신한다.
     *
     * <p><b>지우고 다시 넣지 않는다.</b> 같은 원본 일정은 UQ(connection_id, external_event_id)로 한 행을
     * 유지하며 내용만 갱신한다 — 매번 새로 만들면 사용자가 제외한 일정이 후보로 되살아난다.
     */
    private void synchronize(UUID userId, ExternalCalendarConnection connection) {
        List<ExternalCalendarSelection> selections =
                selectionRepository.findByConnectionIdOrderByCalendarNameAsc(connection.getId());
        if (selections.isEmpty()) {
            return;   // 가져올 캘린더를 아직 고르지 않았다 — 제공자를 부를 이유가 없다.
        }
        ProviderCredential credential = tokens.usableCredential(connection);
        ZoneId zone = userClock.zoneOf(userId);
        LocalDate today = userClock.todayOf(userId);
        Instant from = today.minusDays(SYNC_PAST_DAYS).atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(SYNC_FUTURE_DAYS).atStartOfDay(zone).toInstant();

        Map<String, ExternalCalendarEvent> existing = new HashMap<>();
        for (ExternalCalendarEvent event : eventRepository.findByConnectionId(connection.getId())) {
            existing.put(event.getExternalEventId(), event);
        }

        Instant now = userClock.now();
        List<ExternalCalendarEvent> created = new ArrayList<>();
        for (ExternalCalendarSelection selection : selections) {
            List<ProviderEvent> fetched = providerRegistry.get(connection.getProvider())
                    .listEvents(credential, selection.getExternalCalendarId(), selection.getCalendarName(), from, to);

            for (ProviderEvent providerEvent : fetched) {
                ExternalCalendarEvent stored = existing.get(providerEvent.externalEventId());
                if (stored != null) {
                    stored.resync(providerEvent.title(), providerEvent.startAt(), providerEvent.endAt(),
                            providerEvent.sourceCalendar(), now);
                } else {
                    created.add(ExternalCalendarEvent.candidate(connection.getId(),
                            providerEvent.externalEventId(), providerEvent.title(),
                            providerEvent.startAt(), providerEvent.endAt(),
                            providerEvent.sourceCalendar(), now));
                }
            }
        }
        eventRepository.saveAll(created);
    }

    /** 부재와 타인 소유를 같은 404 로 돌린다 — 존재 여부를 알려주지 않는다(NFR-030). */
    private ExternalCalendarConnection requireConnection(UUID userId, UUID connectionId) {
        return connectionRepository.findByIdAndUserId(connectionId, userId)
                .orElseThrow(() -> new OpenPlanException(ErrorCode.E_COM_004));
    }

    private static ExternalCalendarProvider parseProvider(String value) {
        try {
            return ExternalCalendarProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("provider", String.valueOf(value)));
        }
    }

    private static ApplyMode parseMode(String value) {
        try {
            return ApplyMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new OpenPlanException(ErrorCode.E_COM_009, Map.of("mode", String.valueOf(value)));
        }
    }
}
