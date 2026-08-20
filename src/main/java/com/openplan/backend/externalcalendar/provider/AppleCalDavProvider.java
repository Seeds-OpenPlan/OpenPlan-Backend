package com.openplan.backend.externalcalendar.provider;

import com.openplan.backend.externalcalendar.domain.ExternalCalendarProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.ZoneId;

/**
 * 애플(iCloud) 캘린더 어댑터 — CalDAV (ST-B1-11 · ONB-07~09 · FIX-13~17).
 *
 * <p><b>왜 CalDAV 인가.</b> 애플은 서드파티가 쓸 수 있는 캘린더 REST API 를 제공하지 않는다.
 * "Sign in with Apple" 은 <b>신원 확인용</b>이라 캘린더를 읽지 못한다. 열려 있는 경로는 CalDAV 하나이며
 * 인증은 Apple ID + <b>앱 전용 비밀번호</b>의 HTTP Basic 이다 — 2026-08-21 실측:
 * {@code PROPFIND https://caldav.icloud.com/} → {@code 401} · {@code WWW-Authenticate: Basic realm="MMCalDav"}.
 *
 * <p><b>사용자가 앱 전용 비밀번호를 직접 발급해야 한다</b>({@code appleid.apple.com} → 로그인 및 보안 →
 * 앱 암호, <b>2단계 인증 필수</b>). 계정 비밀번호로는 인증되지 않는다. 구글처럼 버튼 한 번으로 끝나지
 * 않는다는 뜻이라, 화면이 발급 경로를 함께 안내해야 한다.
 *
 * <p>🟢 <b>개발자 콘솔 등록·앱 심사·리디렉션 URI 가 전부 필요 없다.</b> OAuth 가 아니라 사용자 자격증명을
 * 직접 쓰기 때문이다. 구글 캘린더가 도메인(HTTPS·비 raw-IP)에 막혀 있는 동안 <b>배포 환경에서 실제로
 * 도는 유일한 연동</b>이 될 수 있다.
 *
 * <p>절차·파싱·반복 전개는 {@link CalDavCalendarProvider} 가 전담한다. 여기서 정하는 것은 호스트와
 * floating 시각의 해석 지역뿐이다.
 *
 * <p>🔴 <b>미검증</b>: iCloud 는 인증 후 사용자별 호스트({@code p##-caldav.icloud.com})로 보내는 것으로
 * 알려져 있다. 리다이렉트 처리와 {@code calendar-query} 의 {@code calendar-data} 동반 여부는
 * 실계정 왕복으로만 확정된다 — {@code AppleCalDavLiveTest} 참조.
 */
@Component
public class AppleCalDavProvider extends CalDavCalendarProvider {

    public AppleCalDavProvider(RestClient calDavRestClient) {
        super(calDavRestClient);
    }

    @Override
    public ExternalCalendarProvider provider() {
        return ExternalCalendarProvider.APPLE;
    }

    @Override
    protected String baseUrl() {
        return "https://caldav.icloud.com";
    }

    /**
     * floating 시각의 해석 지역.
     *
     * <p>이 서비스의 사용자는 한국에 있고 계획 화면도 {@code Asia/Seoul} 기준으로 그린다. UTC 로 읽으면
     * 9시간이 밀리므로, 모를 때는 UTC 가 아니라 이쪽으로 떨어뜨린다. 대부분의 일정은 {@code TZID} 를
     * 달고 오므로 이 값이 쓰이는 경우는 드물다.
     */
    @Override
    protected ZoneId defaultZone() {
        return ZoneId.of("Asia/Seoul");
    }
}
