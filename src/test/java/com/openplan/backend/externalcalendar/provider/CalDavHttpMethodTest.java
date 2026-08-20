package com.openplan.backend.externalcalendar.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CalDAV 확장 메서드가 <b>실제 전선으로 나가는지</b> (ST-B1-11).
 *
 * <p><b>왜 이 테스트가 따로 필요한가.</b> {@code AppleCalDavProviderTest} 는
 * {@code MockRestServiceServer} 로 HTTP 스택을 통째로 대체하므로, 요청 객체에 {@code PROPFIND} 를
 * 담는 것까지만 확인한다. <b>그 요청이 소켓으로 나갈 수 있는지는 전혀 검증하지 않는다</b> — 목이 통과해도
 * 실서버에서는 전송 자체가 실패할 수 있고, 그 차이가 정확히 아래 첫 번째 테스트다.
 *
 * <p>그래서 JDK 내장 {@link HttpServer} 로 진짜 소켓을 열고 왕복시킨다. 의존성을 늘리지 않으면서
 * "전송된다"를 실행으로 남기는 유일한 방법이다.
 */
class CalDavHttpMethodTest {

    private static final HttpMethod PROPFIND = HttpMethod.valueOf("PROPFIND");

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> observedMethod = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            observedMethod.set(exchange.getRequestMethod());
            byte[] body = "<D:multistatus xmlns:D=\"DAV:\"/>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(207, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("CalDAV 클라이언트는 PROPFIND 를 실제로 전송한다")
    void PROPFIND_가_전선으로_나간다() {
        RestClient client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();

        String response = client.method(PROPFIND)
                .uri(baseUrl + "/")
                .header("Depth", "0")
                .body("<d:propfind xmlns:d=\"DAV:\"><d:prop/></d:propfind>")
                .retrieve()
                .body(String.class);

        assertThat(observedMethod.get()).isEqualTo("PROPFIND");
        assertThat(response).contains("multistatus");
    }

    @Test
    @DisplayName("REPORT 도 전송된다 — 일정 조회 2단계가 둘 다 확장 메서드다")
    void REPORT_도_전송된다() {
        RestClient client = RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();

        client.method(HttpMethod.valueOf("REPORT"))
                .uri(baseUrl + "/cal/")
                .header("Depth", "1")
                .body("<c:calendar-query xmlns:c=\"urn:ietf:params:xml:ns:caldav\"/>")
                .retrieve()
                .body(String.class);

        assertThat(observedMethod.get()).isEqualTo("REPORT");
    }

    /**
     * 기본 팩토리로는 못 보낸다는 것을 <b>실행으로 고정</b>한다.
     *
     * <p>이 테스트가 깨진다면 누군가 CalDAV 클라이언트를 공용 빈으로 되돌린 것이다. 그 변경은
     * 컴파일도 되고 단위 테스트도 통과하지만 <b>실서버에서 연동 전체가 죽는다</b> —
     * {@code HttpURLConnection} 이 메서드 이름을 고정 목록으로 검사하기 때문이다.
     */
    @Test
    @DisplayName("기본(HttpURLConnection) 팩토리로는 PROPFIND 를 보낼 수 없다 — 빈을 나눈 이유")
    void 기본_팩토리로는_보낼_수_없다() {
        RestClient client = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();

        assertThatThrownBy(() -> client.method(PROPFIND)
                .uri(baseUrl + "/")
                .body("<d:propfind xmlns:d=\"DAV:\"><d:prop/></d:propfind>")
                .retrieve()
                .body(String.class))
                .isInstanceOf(Exception.class);

        // 서버에 닿지도 못한다 — 전송 이전에 막힌다.
        assertThat(observedMethod.get()).isNull();
    }
}
