package com.openplan.backend.externalcalendar.outbound;

/**
 * 아웃박스 한 건의 상태 (#69).
 *
 * <p>{@code FAILED} 는 «영영 못 보냄» 이 아니라 «지금은 못 보냄» 이다 — 다음 동기화가 다시 집는다.
 * 영구 실패를 따로 두지 않는 이유: 실패의 대부분이 토큰 만료·연결 끊김처럼 <b>사용자가 고치면
 * 풀리는</b> 것이라, 우리가 먼저 포기하면 사용자가 고친 뒤에도 안 나간다.
 */
public enum OutboundStatus {
    PENDING, DONE, FAILED
}
