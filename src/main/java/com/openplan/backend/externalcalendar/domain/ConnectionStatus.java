package com.openplan.backend.externalcalendar.domain;

import java.util.Locale;

/**
 * 연동 상태 — baseline {@code external_calendar_connections.status} (ck_ext_conn_status: ACTIVE/INACTIVE).
 *
 * <p>🔴 <b>DB 와 API 의 어휘가 다르다.</b> 저장은 {@code ACTIVE/INACTIVE} 인데 openapi 는
 * {@code enum: [CONNECTED, DISABLED]} 다. 같은 프로젝트의 다른 리소스는 양쪽이 일치하므로
 * (예: FixedSchedule.status 는 openapi 도 ACTIVE/INACTIVE) <b>연동만 예외</b>다.
 *
 * <p>어느 쪽도 틀린 게 아니라서 DB 를 고치지 않았다 — DB 어휘를 ACTIVE/INACTIVE 로 두면
 * FIX-16 미러(연동 비활성 → 유래 fixed_schedules.status=INACTIVE)가 <b>같은 값끼리의 대응</b>이 되어
 * 오히려 단순하다. 대신 변환을 이 enum 안에 가둬 <b>DB 어휘가 응답으로 새어나갈 수 없게</b> 한다
 * (D-54 — 같은 이름의 필드가 곳마다 다른 규칙을 따르면 쓰는 사람이 한 곳만 보고 전부 그렇다고 믿는다).
 */
public enum ConnectionStatus {

    ACTIVE("CONNECTED"),
    INACTIVE("DISABLED");

    private final String apiValue;

    ConnectionStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    /** 응답에 실리는 값. 저장값을 그대로 내보내면 계약 위반이다. */
    public String apiValue() {
        return apiValue;
    }

    /** 요청의 {@code CONNECTED}/{@code DISABLED} → 저장값. 알 수 없는 값이면 예외. */
    public static ConnectionStatus fromApiValue(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        for (ConnectionStatus status : values()) {
            if (status.apiValue.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("알 수 없는 연동 상태: " + value);
    }
}
