package com.openplan.backend.externalcalendar.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 iCloud 서버 왕복 (ST-B1-11) — <b>자격증명이 있을 때만 돈다.</b>
 *
 * <p><b>왜 따로 두는가.</b> {@code CalDavCalendarProviderTest} 는 응답을 대역하고
 * {@code CalDavHttpMethodTest} 는 로컬 서버를 쓴다. 둘 다 <b>"iCloud 가 우리 요청을 실제로 받아주는가"</b>
 * 는 증명하지 못한다 — 우리가 조립한 요청 XML 이 네이버의 파서를 통과하는지, 우리가 짐작한 응답 구조가
 * 계정마다 같은지는 진짜 왕복으로만 알 수 있다.
 *
 * <p><b>CI 에서는 돌지 않는다.</b> 환경변수가 없으면 통째로 건너뛴다. 자격증명을 저장소에 두지 않기
 * 위해서이고, 외부 서비스 장애가 우리 빌드를 빨갛게 만들지 않게 하기 위해서다.
 *
 * <h2>실행 방법</h2>
 * <pre>
 * # 1. appleid.apple.com 에서 앱 전용 비밀번호 발급 (2단계 인증 필수)
 * #    로그인 및 보안 → 앱 암호 → 생성
 * # 2. 환경변수로 넘겨 실행 (명령 인자로 주면 셸 히스토리에 남는다)
 * set APPLE_CALDAV_ID=아이디
 * set APPLE_CALDAV_PASSWORD=발급된비밀번호
 * gradlew.bat test --tests *AppleCalDavLiveTest*
 * # 3. 끝나면 appleid.apple.com 에서 앱 암호를 삭제한다
 * </pre>
 *
 * <p><b>개인 일정 내용을 로그에 남기지 않는다.</b> 제목·장소는 찍지 않고 건수와 구조만 확인한다 —
 * 검증에 필요한 것은 "파싱이 되는가"이지 "무엇이 들어 있는가"가 아니다.
 */
@EnabledIfEnvironmentVariable(named = "APPLE_CALDAV_ID", matches = ".+")
class AppleCalDavLiveTest {

    private static final Logger log = LoggerFactory.getLogger(AppleCalDavLiveTest.class);

    private final AppleCalDavProvider provider = new AppleCalDavProvider(
            RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build());

    private ProviderCredential credential() {
        return ProviderCredential.basic(
                System.getenv("APPLE_CALDAV_ID"),
                System.getenv("APPLE_CALDAV_PASSWORD"));
    }

    @Test
    @DisplayName("iCloud 실서버 — 캘린더 목록을 가져온다")
    void 캘린더_목록이_온다() {
        List<ProviderCalendar> calendars = provider.listCalendars(credential());

        assertThat(calendars).isNotEmpty();
        assertThat(calendars).allSatisfy(calendar -> {
            assertThat(calendar.externalCalendarId()).startsWith("/");
            assertThat(calendar.name()).isNotBlank();
            // 저장 컬럼이 255자다 — 실제 href 가 그 안에 들어오는지 확인한다.
            assertThat(calendar.externalCalendarId().length()).isLessThanOrEqualTo(255);
        });
        log.info("iCloud 실서버 캘린더 {}개 (이름은 남기지 않는다)", calendars.size());
    }

    @Test
    @DisplayName("iCloud 실서버 — 최근 30일 일정을 파싱한다")
    void 일정을_파싱한다() {
        List<ProviderCalendar> calendars = provider.listCalendars(credential());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(30));

        int total = 0;
        for (ProviderCalendar calendar : calendars) {
            List<ProviderEvent> events = provider.listEvents(
                    credential(), calendar.externalCalendarId(), calendar.name(), from, to);

            assertThat(events).allSatisfy(event -> {
                assertThat(event.externalEventId()).isNotBlank();
                assertThat(event.title()).isNotBlank();
                assertThat(event.startAt()).isNotNull();
                // 끝이 시작보다 앞서면 고정 일정으로 옮길 때 음수 길이가 된다.
                assertThat(event.endAt()).isAfter(event.startAt());
                // 조회 구간과 겹치지 않는 발생이 섞이면 반복 전개가 틀린 것이다.
                assertThat(event.startAt()).isBefore(to);
                assertThat(event.endAt()).isAfter(from);
            });
            assertThat(events).extracting(ProviderEvent::externalEventId).doesNotHaveDuplicates();
            total += events.size();
        }
        log.info("iCloud 실서버 일정 {}건 파싱 (내용은 남기지 않는다)", total);
    }

    @Test
    @DisplayName("iCloud 실서버 — 틀린 비밀번호는 422 로 갈라진다")
    void 틀린_자격증명은_422() {
        ProviderCredential wrong = ProviderCredential.basic(
                System.getenv("APPLE_CALDAV_ID"), "definitely-not-the-password");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.listCalendars(wrong))
                .isInstanceOf(com.openplan.backend.global.error.OpenPlanException.class)
                .extracting(e -> ((com.openplan.backend.global.error.OpenPlanException) e).errorCode())
                .isEqualTo(com.openplan.backend.global.error.ErrorCode.E_EXT_002);
    }
}
