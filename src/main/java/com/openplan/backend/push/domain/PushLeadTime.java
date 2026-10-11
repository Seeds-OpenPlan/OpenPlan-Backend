package com.openplan.backend.push.domain;

import java.util.List;

/**
 * 푸시 알림 시간 — 시작 몇 분 전에 보낼지 (ADR-0015 개정 · 2026-10-11 사용자 결정).
 *
 * <p><b>정해진 보기 중 하나만</b> 받는다. 보기는 DB CHECK({@code ck_push_settings_lead_minutes})·openapi
 * {@code PushSettings.leadMinutes} enum 과 1:1 — 하나를 늘리면 셋을 함께 고친다.
 *
 * <p>사용자당 <b>하나</b>다. 켠 항목(태스크·고정 일정·일정) 전부에 같은 시간이 적용된다(사용자 결정).
 */
public final class PushLeadTime {

    /** 정각 · 5분 · 10분 · 15분 · 30분 · 1시간 전. */
    public static final List<Integer> ALLOWED_MINUTES = List.of(0, 5, 10, 15, 30, 60);

    /** 가장 긴 보기 — 대상 조회가 {@code start_at} 범위를 거칠게 먼저 좁히는 데 쓴다. */
    public static final int MAX_MINUTES = 60;

    /** 처음 쓰는 사용자의 기본값(사용자 결정 — 전부 켜짐 · 10분 전). */
    public static final int DEFAULT_MINUTES = 10;

    private PushLeadTime() {
    }

    public static boolean isAllowed(Integer minutes) {
        return minutes != null && ALLOWED_MINUTES.contains(minutes);
    }

    /** 알림 본문의 앞부분 — 「지금 시작합니다」 · 「10분 뒤 시작합니다」 · 「1시간 뒤 시작합니다」. */
    public static String phrase(int minutes) {
        if (minutes == 0) {
            return "지금 시작합니다";
        }
        if (minutes % 60 == 0) {
            return (minutes / 60) + "시간 뒤 시작합니다";
        }
        return minutes + "분 뒤 시작합니다";
    }
}
