package com.openplan.backend.push.service;

import com.openplan.backend.push.domain.PushSubscription;

/**
 * 발송 포트 — 실제 HTTP 전송은 {@code HttpWebPushSender}(운영), 테스트는 네트워크를 타지 않는 가짜
 * 구현을 주입한다({@code PushReminderDispatcher}가 이 인터페이스만 안다). 디스패처가 전송 성공/구독
 * 만료를 구분해 처리해야 하므로(ADR-0015 결정 ④ — 404/410이면 구독 삭제) 결과를 {@link Outcome}으로 좁힌다.
 */
public interface WebPushSender {

    /**
     * @param subscription 발송 대상 구독(endpoint·p256dh·auth)
     * @param payloadJson  암호화 전 평문 JSON — RFC 8291 암호화·VAPID 서명은 구현체 책임
     */
    Outcome send(PushSubscription subscription, String payloadJson);

    enum Outcome {
        SENT,
        /** 404/410 — 구독이 더는 유효하지 않다. 호출자가 구독 행을 지운다(ADR-0015 결정 ④). */
        SUBSCRIPTION_GONE,
        /**
         * 그 외 실패(네트워크·5xx 등) — 발송 전에 이미 {@code push_deliveries}를 찍었으므로 다음 틱이
         * 같은 대상을 재시도하지 않는다(재전송 폭주 방지가 목적 — ADR-0015 과업 설명에 "실패 시 WARN 로그만
         * 남기고 넘어간다. 그 발송분은 유실로 간주한다"로 명시된 트레이드오프).
         */
        FAILED
    }
}
