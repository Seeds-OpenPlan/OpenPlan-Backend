package com.openplan.backend.externalcalendar.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
}
