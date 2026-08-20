package com.openplan.backend.externalcalendar.domain;

import com.openplan.backend.auth.oauth.OAuthProviderType;

/**
 * 외부 캘린더 제공자 — baseline {@code external_calendar_connections.provider} (ck_ext_conn_provider).
 *
 * <p><b>{@link com.openplan.backend.user.domain.SocialProvider}와 값이 같은데 왜 따로 두는가.</b>
 * 로그인 가능한 제공자와 <b>캘린더를 읽을 수 있는</b> 제공자가 같지 않고, <b>읽는 방식도 같지 않기</b>
 * 때문이다. 두 집합을 한 enum 으로 묶으면 "로그인 되니 연동도 같은 방식이겠지"라는 오독이 코드 안에 남는다.
 *
 * <p><b>애플은 인증 모델이 다르다.</b> 애플은 캘린더용 공개 REST API 를 제공하지 않는다.
 * iCloud 캘린더에 접근하는 문서화된 경로는 CalDAV({@code caldav.icloud.com}) 뿐이고, 인증은
 * OAuth 가 아니라 <b>Apple ID + 앱 암호(app-specific password)</b> 의 HTTP Basic 이다.
 * 그래서 애플만 {@link CalendarAuthModel#CALDAV_BASIC} 이고, 인가 코드 대신 Apple ID 와
 * 앱 암호를 받는다. 앱 암호는 2단계 인증이 켜진 계정에서만 발급된다.
 *
 * <p>openapi 의 {@code createConnection} 은 이 갈라짐을 {@code oneOf} + {@code discriminator(provider)} 로
 * 표현한다 — 오퍼레이션을 쪼개지 않은 것은 연동 목록·해제·캘린더 선택이 제공자와 무관하게 같은 자원을
 * 쓰기 때문이다. 반면 {@code startCalendarAuthorization} 의 provider 는 {@code [GOOGLE]} 만 남는다 —
 * <b>애플이 빠진 것은 누락이 아니라 인가 단계 자체가 없기 때문이다.</b>
 */
public enum ExternalCalendarProvider {

    GOOGLE(OAuthProviderType.GOOGLE, "https://www.googleapis.com/auth/calendar.readonly",
            CalendarAuthModel.OAUTH),

    /**
     * CalDAV Basic — 캘린더용 공개 REST API 가 없어 인가가 아니라 앱 암호로 붙는다.
     *
     * <p>{@code oauthProvider} 가 {@code null} 인 것은 누락이 아니다. 애플은 우리 소셜 로그인
     * 제공자가 아니고({@link OAuthProviderType} 에 없다), CalDAV 경로는 인가·토큰 교환·갱신을
     * 하나도 쓰지 않는다. 없는 값을 억지로 채우면 "애플로 로그인" 이 되는 줄 아는 코드가 생긴다.
     */
    APPLE(null, "", CalendarAuthModel.CALDAV_BASIC);

    /**
     * 제공자에 붙는 방식. 연동 생성이 무엇을 요구하는지가 여기서 갈린다.
     *
     * <p>이것을 제공자별 {@code if} 로 흩어 두면 "애플인데 state 를 검증"하는 식의 조합이 조용히
     * 생긴다. 갈림길을 값으로 만들어 한 곳에서만 분기한다.
     */
    public enum CalendarAuthModel {
        /** 인가 코드 → 토큰 교환. 토큰은 만료되고 refresh 로 갱신한다. */
        OAUTH,
        /**
         * 아이디 + 애플리케이션 비밀번호를 Basic 으로 매 요청에 싣는다.
         *
         * <p>만료가 없다 — 사용자가 애플 계정 보안 설정에서 <b>회수</b>할 때까지 유효하다. 그래서 연결에
         * refresh 토큰도 만료 시각도 없다.
         */
        CALDAV_BASIC
    }

    private final OAuthProviderType oauthProvider;
    private final String calendarScope;
    private final CalendarAuthModel authModel;

    ExternalCalendarProvider(OAuthProviderType oauthProvider, String calendarScope,
                             CalendarAuthModel authModel) {
        this.oauthProvider = oauthProvider;
        this.calendarScope = calendarScope;
        this.authModel = authModel;
    }

    public CalendarAuthModel authModel() {
        return authModel;
    }

    /** 인가 코드 교환 경로를 타는가 — 아니면 자격증명을 직접 받는가. */
    public boolean usesOAuth() {
        return authModel == CalendarAuthModel.OAUTH;
    }

    /**
     * 캘린더 <b>읽기</b>에 필요한 scope — 로그인 scope와 다르다.
     *
     * <p>로그인은 신원만 확인하면 되므로 {@code openid email profile} 이면 끝이지만, 캘린더는 남의 일정을
     * 읽는 권한이라 별도 동의가 필요하다. 로그인 scope 에 이것을 섞지 않는 이유는, 캘린더를 쓰지 않는
     * 사용자에게까지 <b>일정 열람 동의를 요구하게</b> 되기 때문이다(최소 권한).
     */
    public String calendarScope() {
        return calendarScope;
    }

    /**
     * 인가 코드 교환에 쓰는 OAuth 제공자 정의(엔드포인트·자격증명 조회 키).
     *
     * <p><b>{@link CalendarAuthModel#OAUTH} 제공자에만 있다.</b> CalDAV 제공자에서 부르면
     * 예외를 던진다 — null 을 그대로 돌려주면 호출부가 NPE 로 죽으면서 "무엇이 잘못됐는지"를
     * 잃는다. 인가 단계가 없는 제공자에 인가를 요구한 것이므로, 그 사실을 그대로 말한다.
     */
    public OAuthProviderType oauthProvider() {
        if (oauthProvider == null) {
            throw new IllegalStateException(
                    name() + " 는 " + authModel + " 이라 OAuth 제공자 정의가 없다 — 인가 경로를 태우지 말 것");
        }
        return oauthProvider;
    }
}
