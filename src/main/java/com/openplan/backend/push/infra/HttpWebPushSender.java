package com.openplan.backend.push.infra;

import com.openplan.backend.push.crypto.EcKeys;
import com.openplan.backend.push.crypto.VapidJwtSigner;
import com.openplan.backend.push.crypto.WebPushEncryptor;
import com.openplan.backend.push.domain.PushSubscription;
import com.openplan.backend.push.service.WebPushSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * 표준 Web Push 전송 — RFC 8291(암호화) + RFC 8292(VAPID) + {@link HttpClient} (ADR-0015 결정 ⑤).
 *
 * <p>이 빈은 VAPID 설정 여부와 무관하게 항상 등록된다 — "비어 있으면 아무것도 하지 않는다"의 분기점은
 * {@code PushReminderDispatcher}(틱 진입 시 {@link VapidProperties#isConfigured()} 확인)에 있다.
 * 여기서 또 막으면 판단 지점이 둘로 갈라진다.
 */
@Component
public class HttpWebPushSender implements WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(HttpWebPushSender.class);

    /** RFC 8291 §4: 단일 레코드. salt는 매 발송 새로 뽑으므로 레코드 재사용에 의한 키 유출 위험이 없다. */
    private static final int AES_GCM_TAG_LENGTH = 16;
    private static final int PADDING_DELIMITER_LENGTH = 1;
    private static final Duration VAPID_TTL = Duration.ofHours(12); // RFC 8292 MUST ≤ 24h — 여유를 두고 절반
    private static final Duration PUSH_TTL = Duration.ofMinutes(30); // 리마인더는 신선하지 않으면 무의미

    private final VapidProperties vapidProperties;
    private final HttpClient httpClient;
    private final SecureRandom random = new SecureRandom();

    public HttpWebPushSender(VapidProperties vapidProperties) {
        this.vapidProperties = vapidProperties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Outcome send(PushSubscription subscription, String payloadJson) {
        try {
            byte[] result = encryptAndSend(subscription, payloadJson);
            log.debug("web push sent subscriptionId={} bytes={}", subscription.getId(), result.length);
            return Outcome.SENT;
        } catch (SubscriptionGoneException e) {
            log.info("web push subscription gone (status={}) subscriptionId={}", e.status, subscription.getId());
            return Outcome.SUBSCRIPTION_GONE;
        } catch (Exception e) {
            // 네트워크·서명·암호화 등 어떤 예외도 디스패처 스레드를 죽이면 안 된다 — 여기서 전부 삼키고
            // FAILED로 넘긴다. push_deliveries 행은 이미 찍혀 있어 같은 대상이 다음 틱에 재시도되지 않는다
            // (ADR-0015 — 유실로 간주하는 트레이드오프, 재시도 폭주를 피하는 쪽을 택했다).
            log.warn("web push send failed subscriptionId={}", subscription.getId(), e);
            return Outcome.FAILED;
        }
    }

    private byte[] encryptAndSend(PushSubscription subscription, String payloadJson) throws Exception {
        URI endpoint = URI.create(subscription.getEndpoint());
        ECPublicKey receiverPublicKey = EcKeys.decodePublicKeyBase64Url(subscription.getP256dh());
        byte[] authSecret = Base64.getUrlDecoder().decode(subscription.getAuth());

        byte[] salt = new byte[16];
        random.nextBytes(salt);
        KeyPair ephemeralKeyPair = generateEphemeralP256KeyPair();

        byte[] plaintext = payloadJson.getBytes(StandardCharsets.UTF_8);
        int recordSize = plaintext.length + PADDING_DELIMITER_LENGTH + AES_GCM_TAG_LENGTH + 1; // RFC §4: "greater than"
        byte[] body = WebPushEncryptor.encrypt(receiverPublicKey, authSecret, salt, ephemeralKeyPair,
                recordSize, plaintext);

        ECPrivateKey vapidPrivateKey = EcKeys.decodePrivateKeyBase64Url(vapidProperties.privateKey());
        String audience = endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() > 0 ? ":" + endpoint.getPort() : "");
        String jwt = VapidJwtSigner.sign(audience, vapidProperties.subject(), Instant.now().plus(VAPID_TTL),
                vapidPrivateKey);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", String.valueOf(PUSH_TTL.toSeconds()))
                .header("Urgency", "high")
                .header("Authorization", "vapid t=" + jwt + ", k=" + vapidProperties.publicKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status == 404 || status == 410) {
            throw new SubscriptionGoneException(status);
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("push service responded " + status);
        }
        return body;
    }

    private KeyPair generateEphemeralP256KeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair();
    }

    private static final class SubscriptionGoneException extends RuntimeException {
        private final int status;

        private SubscriptionGoneException(int status) {
            super("push service status " + status);
            this.status = status;
        }
    }
}
