package com.openplan.backend.auth.domain;

/**
 * 링크 토큰 상태 — {@code auth_tokens.status}({@code ck_auth_tokens_status} CHECK와 1:1).
 *
 * <p>{@code used_at}과 역할이 겹쳐 보이지만 다르다. {@code used_at}은 <b>언제</b> 썼는지(기록),
 * 이 값은 <b>지금 쓸 수 있는지</b>(판정)다. 만료는 시각 비교로도 알 수 있으나, 만료를 확인한 시점에
 * {@link #EXPIRED}로 굳혀 두면 같은 링크가 반복해서 들어와도 시각 계산을 되풀이하지 않는다.
 */
public enum AuthTokenStatus {
    ISSUED,
    USED,
    EXPIRED
}
