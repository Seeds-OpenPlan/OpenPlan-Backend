package com.openplan.backend.push.support;

import com.openplan.backend.push.domain.PushSubscription;
import com.openplan.backend.push.service.WebPushSender;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 네트워크를 타지 않는 발송기 — {@code PushReminderDispatcher} 통합 테스트 전용. 실제 암호화·HTTP 전송
 * ({@code HttpWebPushSender})을 대신해 호출만 기록한다(ADR-0015 지시: "테스트는 fake sender, 네트워크
 * 금지"). {@code FakePushConfig}가 {@code @Primary}로 등록해 {@code HttpWebPushSender}를 가린다.
 */
public class FakeWebPushSender implements WebPushSender {

    public record Call(PushSubscription subscription, String payloadJson) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final Map<String, Outcome> outcomeByEndpoint = new ConcurrentHashMap<>();
    private volatile Outcome defaultOutcome = Outcome.SENT;

    @Override
    public Outcome send(PushSubscription subscription, String payloadJson) {
        calls.add(new Call(subscription, payloadJson));
        return outcomeByEndpoint.getOrDefault(subscription.getEndpoint(), defaultOutcome);
    }

    public List<Call> calls() {
        return calls;
    }

    /** 특정 endpoint에 대해서만 결과를 바꾼다 — 구독 삭제(404/410) 경로 테스트용. */
    public void forceOutcome(String endpoint, Outcome outcome) {
        outcomeByEndpoint.put(endpoint, outcome);
    }

    public void reset() {
        calls.clear();
        outcomeByEndpoint.clear();
        defaultOutcome = Outcome.SENT;
    }
}
