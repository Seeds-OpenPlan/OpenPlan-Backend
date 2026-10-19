package com.openplan.backend.push.support;

import com.openplan.backend.push.infra.VapidProperties;
import com.openplan.backend.push.service.WebPushSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 디스패처 통합 테스트 설정 — VAPID를 "설정됨"으로 고정하고(값 자체는 쓰이지 않는다, {@link FakeWebPushSender}가
 * 암호화·서명을 하지 않으므로) 발송은 네트워크 대신 {@link FakeWebPushSender}로 가로챈다.
 *
 * <p>VAPID를 비워 두면 {@code PushReminderDispatcher.runOnce()}가 맨 앞에서 그냥 반환해버려
 * (ADR-0015 결정 ⑤) 선택 로직 자체를 검증할 수 없다 — 그래서 더미 값으로라도 "설정됨"을 만든다.
 */
@TestConfiguration
public class FakePushConfig {

    @Bean
    @Primary
    public VapidProperties fixedVapidProperties() {
        return new VapidProperties("test-public-key", "test-private-key", "mailto:test@openplan.example");
    }

    @Bean
    @Primary
    public WebPushSender fakeWebPushSender() {
        return new FakeWebPushSender();
    }
}
