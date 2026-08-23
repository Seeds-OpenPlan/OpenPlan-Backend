package com.openplan.backend.externalcalendar.domain;

/**
 * 동기화 방식 — baseline {@code external_calendar_connections.sync_mode} (기본값 MANUAL, CHECK 없음).
 *
 * <p><b>MANUAL 하나뿐인 이유</b>: ADR-0006 이 신규 스케줄러를 금지한다. 동기화는 별도 배치가 아니라
 * {@code GET .../events} 호출 시점에 수행한다 — openapi 요약도 "선택 캘린더 기준 <b>동기화 수행 후</b> 반환"이다.
 * 자동 주기 동기화를 열려면 스케줄러 도입 판단이 선행돼야 하므로 값을 미리 만들어 두지 않는다.
 */
public enum SyncMode {
    MANUAL
}
