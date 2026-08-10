package com.openplan.backend.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 링크 토큰 수명·재발송 제한 ({@code op.auth.token}).
 *
 * <p>🔴 <b>두 수명은 명세에 없다.</b> {@code exceptions.md}는 만료 시 응답만 정의하고
 * (E-AUTH-004 · E-AUTH-006), ui-spec·ux-flow-map 어디에도 "몇 시간"이 없다.
 * 값이 없으면 토큰을 발급할 수 없어 기본값을 두되, <b>설정으로 빼서 한 곳에서 바꾸게</b> 했다 —
 * 팀이 다른 값을 정하면 코드가 아니라 여기만 고친다. 메일 본문의 유효 시간 안내도 이 값에서 나온다.
 *
 * <p>재발송 쿨다운 60초는 <b>정본이 있다</b> — ui-spec §AUTH 회원가입:
 * "인증 메일 발송 안내 상태(재발송 버튼, 60초 쿨다운 카운트다운 텍스트)".
 *
 * @param emailVerificationTtl 이메일 인증 링크 수명. 사용자가 메일함을 나중에 열 수 있어 넉넉히 둔다
 * @param passwordResetTtl     재설정 링크 수명. 계정 탈취로 직결되므로 인증 링크보다 짧게 둔다
 * @param resendCooldown       같은 종류의 메일을 다시 보내기까지의 최소 간격(ui-spec 정본)
 */
@ConfigurationProperties(prefix = "op.auth.token")
public record AuthTokenProperties(
        Duration emailVerificationTtl,
        Duration passwordResetTtl,
        Duration resendCooldown) {

    /** 메일 본문의 "{1}시간 동안 유효합니다" 자리에 들어갈 값. */
    public long emailVerificationTtlHours() {
        return emailVerificationTtl.toHours();
    }

    public long passwordResetTtlHours() {
        return passwordResetTtl.toHours();
    }
}
