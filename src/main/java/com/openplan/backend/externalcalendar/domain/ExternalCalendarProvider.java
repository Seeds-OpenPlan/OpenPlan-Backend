package com.openplan.backend.externalcalendar.domain;

import com.openplan.backend.auth.oauth.OAuthProviderType;

/**
 * 외부 캘린더 제공자 — baseline {@code external_calendar_connections.provider} (ck_ext_conn_provider).
 *
 * <p><b>{@link com.openplan.backend.user.domain.SocialProvider}와 값이 같은데 왜 따로 두는가.</b>
 * 로그인 가능한 제공자와 <b>캘린더를 읽을 수 있는</b> 제공자가 같지 않기 때문이다 — 네이버는 로그인은
 * 되지만 캘린더 오픈 API에 조회 계열 엔드포인트가 없다(공식 문서에 {@code createSchedule} 하나뿐, 2026-08-19 확인).
 * 두 집합을 한 enum 으로 묶으면 "로그인 되니 연동도 되겠지"라는 오독이 코드 안에 남는다.
 *
 * <p>openapi 는 연동 생성 요청의 {@code provider}를 <b>{@code enum: [GOOGLE]}</b> 로 못박고 있다
 * (createConnection). CHECK 제약이 세 값을 허용하는 것은 스키마의 여유일 뿐, <b>계약이 여는 것은 구글뿐</b>이다.
 */
public enum ExternalCalendarProvider {

    GOOGLE(OAuthProviderType.GOOGLE, "https://www.googleapis.com/auth/calendar.readonly"),

    /** 연동 대상 아님 — 조회 API 부재. 값은 스키마 호환을 위해 남긴다. */
    NAVER(OAuthProviderType.NAVER, ""),

    /** 톡캘린더 API 는 있으나 사용 권한 승인 전에는 앱 멤버만 호출 가능 — 계약도 아직 열지 않았다. */
    KAKAO(OAuthProviderType.KAKAO, "talk_calendar");

    private final OAuthProviderType oauthProvider;
    private final String calendarScope;

    ExternalCalendarProvider(OAuthProviderType oauthProvider, String calendarScope) {
        this.oauthProvider = oauthProvider;
        this.calendarScope = calendarScope;
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

    /** 인가 코드 교환에 쓰는 OAuth 제공자 정의(엔드포인트·자격증명 조회 키). */
    public OAuthProviderType oauthProvider() {
        return oauthProvider;
    }
}
