package com.openplan.backend.user.domain;

/**
 * 계정 상태 — {@code users.status}({@code ck_users_status} CHECK와 1:1).
 *
 * <p>로그인 판정이 이 값에 걸린다(ST-B1-02 AC2):
 * <ul>
 *   <li>{@link #ACTIVE} — 정상. 이메일 인증 여부는 별도 컬럼({@code is_email_verified})이 판정한다</li>
 *   <li>{@link #LOCKED} — 401 E-AUTH-002. <b>해제 절차는 운영 수동</b>이며 자동 해제 규칙은 US에 없다</li>
 *   <li>{@link #DEACTIVATED} — 30일 복구창(ACCT-04~06). 창 안이면 409 E-AUTH-008(재활성화 안내),
 *       {@code scheduled_deletion_at} 경과 뒤면 410 E-AUTH-009</li>
 * </ul>
 *
 * <p>삭제 완료 계정은 이 열거에 없다 — 행 자체가 CASCADE로 사라지므로 상태가 아니라 부재로 표현된다.
 */
public enum UserStatus {
    ACTIVE,
    LOCKED,
    DEACTIVATED
}
