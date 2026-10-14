package com.openplan.backend.push.infra;

import com.openplan.backend.push.domain.PushSubscription;
import com.openplan.backend.push.service.WebPushSender.Outcome;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 발송 어댑터 {@link HttpWebPushSender}를 <b>실제 HTTP로</b> 돌린다(PR #93 리뷰 — 디스패처 테스트는
 * {@code FakeWebPushSender}로 이 클래스를 가려 {@code encryptAndSend}가 한 번도 실행되지 않았다).
 * 로컬 {@link HttpServer}를 푸시 서비스로 세워, 요청 모양(헤더·VAPID 토큰·RFC 8188 본문 헤더)과
 * 응답 코드 → {@link Outcome} 매핑을 본다. 암호문 바이트 자체의 정확성은 {@code WebPushEncryptorTest}
 * (RFC 8291 §5 벡터)가 맡는다.
 */
class HttpWebPushSenderTest {

    // RFC 8291 §5 의 수신 측 키·auth — 유효한 P-256 점이면 무엇이든 되지만, 이미 검증된 값을 쓴다.
    private static final String RECEIVER_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH_SECRET = "BTBZMqHH6r4Tts7J_aSIgg";
    // 같은 RFC 예시의 발신 키쌍을 VAPID 서버 키로 재사용한다.
    private static final String VAPID_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String VAPID_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String SUBJECT = "mailto:ops@openplan.test";
    private static final String PAYLOAD = "{\"title\":\"회의\",\"body\":\"10분 뒤 시작합니다 · 09:00\"}";

    private HttpServer server;
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    private volatile int responseStatus = 201;

    private record Captured(String contentEncoding, String contentType, String ttl, String urgency,
                            String authorization, byte[] body) {
    }

    @BeforeEach
    void startPushService() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/push/", exchange -> {
            var h = exchange.getRequestHeaders();
            captured.set(new Captured(h.getFirst("Content-Encoding"), h.getFirst("Content-Type"),
                    h.getFirst("TTL"), h.getFirst("Urgency"), h.getFirst("Authorization"),
                    exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopPushService() {
        server.stop(0);
    }

    @Test
    @DisplayName("201 → SENT. aes128gcm 본문·VAPID 헤더(aud=푸시 서비스 오리진, k=공개키)·TTL 30분으로 보낸다")
    void sendsEncryptedRequestWithVapidHeaders() throws Exception {
        responseStatus = 201;

        Outcome outcome = sender().send(subscription(endpoint()), PAYLOAD);

        assertThat(outcome).isEqualTo(Outcome.SENT);
        Captured req = captured.get();
        assertThat(req.contentEncoding()).isEqualTo("aes128gcm");
        assertThat(req.contentType()).isEqualTo("application/octet-stream");
        assertThat(req.ttl()).isEqualTo("1800");
        assertThat(req.urgency()).isEqualTo("high");

        // Authorization: vapid t=<JWT>, k=<공개키> (RFC 8292 §3)
        assertThat(req.authorization()).startsWith("vapid t=").endsWith(", k=" + VAPID_PUBLIC);
        String jwt = req.authorization().substring("vapid t=".length(), req.authorization().indexOf(", k="));
        String claims = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(claims).contains("\"aud\":\"" + origin() + "\"").contains("\"sub\":\"" + SUBJECT + "\"");

        // RFC 8188 헤더: salt(16) | rs(4) | idlen(1)=65 | keyid(65 — 1회용 발신 공개키) | 암호문
        byte[] plaintext = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        ByteBuffer body = ByteBuffer.wrap(req.body());
        body.position(16);
        assertThat(body.getInt()).isEqualTo(plaintext.length + 1 + 16 + 1);
        assertThat(body.get()).isEqualTo((byte) 65);
        assertThat(req.body()[21]).isEqualTo((byte) 0x04); // 비압축 점 접두
        assertThat(req.body()).hasSize(16 + 4 + 1 + 65 + plaintext.length + 1 + 16);
    }

    @Test
    @DisplayName("410·404 → SUBSCRIPTION_GONE (디스패처가 구독 행을 지운다)")
    void goneStatusesMapToSubscriptionGone() {
        responseStatus = 410;
        assertThat(sender().send(subscription(endpoint()), PAYLOAD)).isEqualTo(Outcome.SUBSCRIPTION_GONE);
        responseStatus = 404;
        assertThat(sender().send(subscription(endpoint()), PAYLOAD)).isEqualTo(Outcome.SUBSCRIPTION_GONE);
    }

    @Test
    @DisplayName("5xx·연결 실패 → FAILED, 예외를 올리지 않는다(디스패처 스레드 보호)")
    void otherFailuresMapToFailedWithoutThrowing() {
        responseStatus = 500;
        assertThat(sender().send(subscription(endpoint()), PAYLOAD)).isEqualTo(Outcome.FAILED);

        String deadEndpoint = endpoint();
        server.stop(0);
        assertThat(sender().send(subscription(deadEndpoint), PAYLOAD)).isEqualTo(Outcome.FAILED);
    }

    // ---------------------------------------------------------------- 픽스처

    private HttpWebPushSender sender() {
        return new HttpWebPushSender(new VapidProperties(VAPID_PUBLIC, VAPID_PRIVATE, SUBJECT));
    }

    private String origin() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    private String endpoint() {
        return origin() + "/push/" + UUID.randomUUID();
    }

    /** 운영 코드에는 생성 경로가 upsert(SQL) 하나뿐이라, 테스트는 JPA 기본 생성자로 만들어 값을 채운다. */
    private static PushSubscription subscription(String endpoint) {
        PushSubscription s = BeanUtils.instantiateClass(PushSubscription.class);
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(s, "endpoint", endpoint);
        ReflectionTestUtils.setField(s, "p256dh", RECEIVER_PUBLIC);
        ReflectionTestUtils.setField(s, "auth", AUTH_SECRET);
        return s;
    }
}
