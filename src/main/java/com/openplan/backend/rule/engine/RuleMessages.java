package com.openplan.backend.rule.engine;

import java.time.DayOfWeek;

/**
 * 판정 사유 문구 (C-3: reason 는 null 불가).
 *
 * ⚠️ PROVISIONAL — 아래 문구는 임시다.
 *    계약(rule-engine-contract §3.3)상 문구 정본은 {@code resources/rules/messages.properties}이고,
 *    골든 테스트가 문구까지 고정한다. 그 정본 텍스트가 아직 없어 임시로 둠 — BE-3(김에스더/전창현) 확정 필요.
 * C-1 준수: 파일 IO 없이 코드 상수만 사용(엔진 순수성 유지).
 */
final class RuleMessages {
    private RuleMessages() {}

    /** V3 가용시간 초과. (요일 표기·정확한 문구는 확정 필요) */
    static String v3CapacityExceeded(DayOfWeek weekday) {
        return weekday + " 요일 배치 시간이 가용 시간을 초과했습니다."; // TODO: 문구 확정(messages.properties)
    }
}
