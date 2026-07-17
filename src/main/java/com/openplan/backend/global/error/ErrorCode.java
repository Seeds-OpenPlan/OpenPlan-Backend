package com.openplan.backend.global.error;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드 SSOT (exceptions.md §2 전수 목록과 1:1).
 *
 * <p>규약: {@code E-{도메인}-{3자리}}. 코드는 안정 식별자이며 연번이 아니다 —
 * 결번은 의도적이고 도메인 내 재부여/삭제 코드 번호 재사용은 금지. 새 코드 추가는
 * 본 enum + openapi.yaml 동시 갱신(W6 3자 대조 테스트 대상).
 *
 * <p>FE는 {@code error.code}로만 분기한다(message 파싱 금지). message는 사용자에게
 * 그대로 노출 가능한 한국어이며 P4(AI 표현 금지)를 준수한다.
 * TODO(ST-B1-01b~): 메시지 문구를 {@code messages/errors.properties} 카탈로그로
 * 외부화하여 P4 grep 감사 단일 지점화(현재는 enum 내 기본 문구).
 */
public enum ErrorCode {

    // 2.1 공통 (E-COM) — 전 라우트
    E_COM_001("E-COM-001", HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
    E_COM_002("E-COM-002", HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    E_COM_003("E-COM-003", HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    E_COM_004("E-COM-004", HttpStatus.NOT_FOUND, "요청하신 리소스를 찾을 수 없습니다."),
    E_COM_005("E-COM-005", HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),
    E_COM_006("E-COM-006", HttpStatus.CONFLICT, "다른 곳에서 먼저 저장되어 충돌이 발생했습니다."),
    E_COM_007("E-COM-007", HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요."),
    E_COM_008("E-COM-008", HttpStatus.SERVICE_UNAVAILABLE, "현재 서비스를 이용할 수 없습니다."),
    E_COM_009("E-COM-009", HttpStatus.UNPROCESSABLE_ENTITY, "입력한 값의 형식이 올바르지 않습니다."),

    // 2.2 인증·계정 (E-AUTH · E-USER) — BE-1
    E_AUTH_001("E-AUTH-001", HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    E_AUTH_002("E-AUTH-002", HttpStatus.UNAUTHORIZED, "잠금된 계정입니다."),
    E_AUTH_003("E-AUTH-003", HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    E_AUTH_004("E-AUTH-004", HttpStatus.GONE, "이메일 인증 링크가 만료되었습니다."),
    E_AUTH_005("E-AUTH-005", HttpStatus.FORBIDDEN, "이메일 인증이 완료되지 않은 계정입니다."),
    E_AUTH_006("E-AUTH-006", HttpStatus.GONE, "재설정 링크가 만료되었거나 이미 사용되었습니다."),
    E_AUTH_007("E-AUTH-007", HttpStatus.UNAUTHORIZED, "세션이 만료되었습니다. 다시 로그인해 주세요."),
    E_AUTH_008("E-AUTH-008", HttpStatus.CONFLICT, "비활성화된 계정입니다."),
    E_AUTH_009("E-AUTH-009", HttpStatus.GONE, "삭제된 계정입니다."),
    E_AUTH_010("E-AUTH-010", HttpStatus.FOUND, "소셜 로그인에 실패했습니다."),
    E_AUTH_011("E-AUTH-011", HttpStatus.NOT_IMPLEMENTED, "아직 제공되지 않는 기능입니다."),
    E_USER_001("E-USER-001", HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다."),

    // 2.3 프로젝트·태스크·WBS (E-PROJ · E-WBS) — BE-2
    E_PROJ_003("E-PROJ-003", HttpStatus.UNPROCESSABLE_ENTITY, "허용되지 않는 상태 전이입니다."),
    E_WBS_001("E-WBS-001", HttpStatus.UNPROCESSABLE_ENTITY, "종료일이 시작일보다 빠를 수 없습니다."),

    // 2.4 주간 계획 (E-PLAN) — BE-2
    E_PLAN_002("E-PLAN-002", HttpStatus.UNPROCESSABLE_ENTITY, "블록의 시작 시각이 종료 시각보다 늦을 수 없습니다."),
    E_PLAN_004("E-PLAN-004", HttpStatus.CONFLICT, "차단 항목이 남아 있어 저장할 수 없습니다."),

    // 2.5 외부 연동 (E-EXT) — BE-1
    E_EXT_001("E-EXT-001", HttpStatus.BAD_GATEWAY, "외부 서비스 연동에 실패했습니다."),
    E_EXT_004("E-EXT-004", HttpStatus.CONFLICT, "이미 연결된 외부 계정입니다.");

    private final String code;
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(String code, HttpStatus status, String defaultMessage) {
        this.code = code;
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
