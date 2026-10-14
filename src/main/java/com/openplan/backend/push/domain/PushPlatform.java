package com.openplan.backend.push.domain;

/**
 * 구독 플랫폼 (ADR-0015 결정 ④) — 현재 안드로이드 앱만 지원. 값 이름은 DB CHECK(ck_push_sub_platform)·
 * openapi {@code PushSubscribeRequest.platform} 과 1:1 고정.
 *
 * <p>FE가 앱 판별 시에만 구독을 만들지만, 이 값은 <b>클라이언트가 보내는 신고값</b>이라 서버가
 * {@code ANDROID_APP} 외의 값을 거절하는 것(422)이 할 수 있는 전부다 — 웹에서 위조해 보내는 경우까지
 * 막지는 않는다(ADR-0015 결정 ④ 명시).
 */
public enum PushPlatform {
    ANDROID_APP
}
