package com.openplan.backend.global.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * 시간 소스 단일 port — <b>크로스도메인 공통 seam</b> (ST-B2-01 P-2 · Q-G에서 도입, global 승격).
 *
 * <ul>
 *   <li>{@link #now()} — 영속 시각(createdAt·closedAt·자동종료 평가 now)의 단일 소스.</li>
 *   <li>{@link #todayOf(UUID)} — 사용자 timezone 기준 오늘. 과거 마감일 검증(Q-J)·자동종료 판정(T7)·
 *       재개 가드(G-1)뿐 아니라 DASH(오늘 할 일·요일별)·PLAN(주차·가용시간 요일)·STAT(구간별)·
 *       NOTI(마감 임박)·FIX(요일별 가용시간) 등 "사용자의 오늘/지금"을 다루는 전 도메인이 공유한다.</li>
 * </ul>
 *
 * <p>엔티티·서비스가 {@code Instant.now()}/{@code LocalDate.now()}를 직접 호출하지 않고 이 port를 경유하므로,
 * 테스트에서 고정 주입해 날짜 경계·정렬을 결정적으로 검증할 수 있다(C4). 도메인마다 "오늘"을 재구현해
 * 경계가 어긋나는 드리프트를 구조적으로 차단한다.
 */
public interface UserClock {

    /** 현재 시각(UTC Instant). */
    Instant now();

    /**
     * 사용자 timezone 기준 오늘. timezone은 {@code user_profiles.timezone}에서 조회하며,
     * 행 부재/미설정 시 {@code Asia/Seoul} fallback(Q-G).
     */
    LocalDate todayOf(UUID userId);

    /**
     * 사용자 timezone {@link ZoneId}. {@link #todayOf(UUID)}와 동일 소스({@code user_profiles.timezone})·
     * 동일 fallback(Asia/Seoul). 요일·자정 경계 판정이 필요한 곳(예: 검증 스냅샷 조립 — ST-B2-09)이 쓴다.
     */
    ZoneId zoneOf(UUID userId);
}
