package com.openplan.backend.auth.domain;

/**
 * 세션 상태 — {@code auth_sessions.status}({@code ck_auth_sessions_status} CHECK와 1:1).
 *
 * <p>논리모델의 '유효/만료/종료'를 영문으로 확정한 값이다(V1 baseline 주석 — 열거 사전에는 없는 레인 로컬 값).
 *
 * <ul>
 *   <li>{@link #ACTIVE} — 회전 대상. refresh 제시 시 이 상태여야만 새 토큰이 나간다</li>
 *   <li>{@link #EXPIRED} — 회전으로 소진됨. <b>정상 종료</b>이며, 이 상태의 해시가 다시 오면 재사용이다</li>
 *   <li>{@link #REVOKED} — 로그아웃·재사용 탐지·비밀번호 변경 등으로 폐기됨</li>
 * </ul>
 *
 * <p><b>EXPIRED와 REVOKED를 구분해 두는 이유</b>는 재사용 탐지의 사후 판독 때문이다. 회전 시 소진된 세션을
 * 지우지 않고 EXPIRED로 남기므로, 같은 해시가 다시 오면 "이미 쓴 토큰이 또 왔다"는 것을 알 수 있다.
 * 행을 삭제했다면 그 요청은 그냥 '모르는 토큰'이 되어 탈취 신호를 잃는다(ADR-0001).
 */
public enum AuthSessionStatus {
    ACTIVE,
    EXPIRED,
    REVOKED
}
