package com.openplan.backend.externalcalendar.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 제공자 호출용 HTTP 클라이언트 (ST-B1-11 AC1).
 *
 * <p><b>타임아웃을 한 곳에 둔다</b> — 연결 3초 · 응답 10초. 어댑터마다 각자 잡으면 제공자가 늘어날수록
 * 값이 갈라지고, 갈라진 것은 느린 제공자 하나가 요청 스레드를 오래 잡을 때에야 드러난다.
 *
 * <p><b>서버 자동 재시도는 없다</b>(AC1). 사용자가 화면 앞에서 기다리는 동기 경로라 재시도는 실패를
 * 늦게 알려줄 뿐이고, 그 사이 스레드가 묶인다.
 */
@Configuration
public class ExternalCalendarClientConfig {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    public RestClient externalCalendarRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return builder.requestFactory(factory).build();
    }

    /**
     * CalDAV 전용 클라이언트 (애플).
     *
     * <p><b>왜 위 빈을 같이 못 쓰는가.</b> {@link SimpleClientHttpRequestFactory} 는
     * {@code HttpURLConnection} 기반인데, 그 구현은 메서드 이름을 GET·POST·HEAD·OPTIONS·PUT·DELETE·TRACE
     * 로 <b>고정 검사</b>한다. CalDAV 가 쓰는 {@code PROPFIND}·{@code REPORT} 는 그 목록에 없어
     * {@code ProtocolException} 으로 막힌다 — 서버에 닿지도 못한다.
     *
     * <p>JDK {@code HttpClient} 기반 팩토리는 메서드 이름을 검사하지 않아 확장 메서드가 그대로 나간다.
     * 타임아웃 값은 위와 같게 유지한다 — 제공자가 달라도 사용자가 기다리는 시간은 같아야 한다.
     */
    @Bean
    public RestClient calDavRestClient(RestClient.Builder builder) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(READ_TIMEOUT);
        return builder.requestFactory(factory).build();
    }
}
