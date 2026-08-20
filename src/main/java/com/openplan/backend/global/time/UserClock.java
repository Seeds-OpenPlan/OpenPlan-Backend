package com.openplan.backend.global.time;

import com.openplan.backend.common.Weekday;

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
 *   <li>{@link #weekStartDayOf(UUID)} — dashboard/stats가 baseDate로부터 "이번 주"를 계산할 때(공용
 *       {@code common.WeekRange}) 쓰는 사용자 설정. timezone과 같은 {@code user_profiles} 행이라 이 port에
 *       같이 둔다(별도 조회 seam을 늘리지 않음).</li>
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
     * 사용자 timezone(zone id). {@link #todayOf(UUID)}와 같은 조회원 — LocalDate↔Instant 경계 변환이
     * 필요한 소비처(STAT 기간 집계 등)가 자체적으로 timezone을 재조회하지 않도록 노출한다.
     */
    ZoneId zoneOf(UUID userId);

    /**
     * 사용자가 설정한 주 시작 요일. {@code user_profiles.week_start_day}에서 조회하며,
     * 행 부재/미설정/미인식 값 시 {@code MON} fallback(DB 컬럼 기본값과 동일 — Q-G와 같은 원칙).
     */
    Weekday weekStartDayOf(UUID userId);
}
