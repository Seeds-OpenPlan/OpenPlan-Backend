package com.openplan.backend.global.error;

import org.springframework.http.HttpStatus;

/**
 * 오류 코드 SSOT (exceptions.md §2 전수 목록과 1:1).
 *
 * <p>규약: {@code E-{도메인}-{3자리}}. 코드는 안정 식별자이며 연번이 아니다 —
 * 결번은 의도적이고 도메인 내 재부여/삭제 코드 번호 재사용은 금지. 새 코드 추가는
 * 본 enum + openapi.yaml 동시 갱신(W6 3자 대조 테스트 대상).
 *
 * <p>FE는 {@code error.code}로만 분기한다(message 파싱 금지).
 *
 * <p><b>문구는 이 enum에 없다</b> — 사용자 노출 문구의 유일한 원본은
 * {@code messages/errors.properties}이며 해석은 {@link ErrorMessages}가 담당한다
 * (exceptions.md §1). 문구가 두 곳에 있으면 P4(AI 표현 금지) grep 감사의 단일 지점이
 * 무너지므로 여기에 되돌려 넣지 말 것. 카탈로그 키 누락은 {@code ErrorMessageCatalogTest}가 잡는다.
 */
public enum ErrorCode {

    // 2.1 공통 (E-COM) — 전 라우트
    E_COM_001("E-COM-001", HttpStatus.BAD_REQUEST),
    E_COM_002("E-COM-002", HttpStatus.UNAUTHORIZED),
    E_COM_003("E-COM-003", HttpStatus.FORBIDDEN),
    E_COM_004("E-COM-004", HttpStatus.NOT_FOUND),
    E_COM_005("E-COM-005", HttpStatus.INTERNAL_SERVER_ERROR),
    E_COM_006("E-COM-006", HttpStatus.CONFLICT),
    E_COM_007("E-COM-007", HttpStatus.TOO_MANY_REQUESTS),
    E_COM_008("E-COM-008", HttpStatus.SERVICE_UNAVAILABLE),
    E_COM_009("E-COM-009", HttpStatus.UNPROCESSABLE_ENTITY),

    // 2.2 인증·계정 (E-AUTH · E-USER) — BE-1
    E_AUTH_001("E-AUTH-001", HttpStatus.UNAUTHORIZED),
    E_AUTH_002("E-AUTH-002", HttpStatus.UNAUTHORIZED),
    E_AUTH_003("E-AUTH-003", HttpStatus.CONFLICT),
    E_AUTH_004("E-AUTH-004", HttpStatus.GONE),
    E_AUTH_005("E-AUTH-005", HttpStatus.FORBIDDEN),
    E_AUTH_006("E-AUTH-006", HttpStatus.GONE),
    E_AUTH_007("E-AUTH-007", HttpStatus.UNAUTHORIZED),
    E_AUTH_008("E-AUTH-008", HttpStatus.CONFLICT),
    E_AUTH_009("E-AUTH-009", HttpStatus.GONE),
    E_AUTH_010("E-AUTH-010", HttpStatus.FOUND),
    E_AUTH_011("E-AUTH-011", HttpStatus.NOT_IMPLEMENTED),
    E_USER_001("E-USER-001", HttpStatus.BAD_REQUEST),

    // 2.3 프로젝트·태스크·WBS (E-PROJ · E-WBS) — BE-2
    E_PROJ_003("E-PROJ-003", HttpStatus.UNPROCESSABLE_ENTITY),
    E_PROJ_004("E-PROJ-004", HttpStatus.UNPROCESSABLE_ENTITY),   // ST-B2-01 G-1 — 마감 경과 재개 불가(마감일 변경 필요)
    E_PROJ_005("E-PROJ-005", HttpStatus.UNPROCESSABLE_ENTITY),   // 종료된 프로젝트에서 수정 불가(재개 먼저). ST-B2-01 프로젝트 편집 + ST-B2-03 CLOSED 하위 태스크 쓰기 4종(D-10) 공용. 전이불허(E-PROJ-003)와 구분
    E_PROJ_006("E-PROJ-006", HttpStatus.UNPROCESSABLE_ENTITY),   // ST-B2-01 — 마감 경과 프로젝트(자동종료 제외된 PAUSED) 편집 시 과거 마감일 유지 불가(마감일 변경 필요). 재개 가드(E-PROJ-004)와 대칭
    E_WBS_001("E-WBS-001", HttpStatus.UNPROCESSABLE_ENTITY),
    E_CAT_001("E-CAT-001", HttpStatus.CONFLICT),                 // ST-B2-04 — 카테고리 이름 중복(UNIQUE(user_id,name))

    // 2.4 주간 계획 (E-PLAN) — BE-2
    E_PLAN_002("E-PLAN-002", HttpStatus.UNPROCESSABLE_ENTITY),
    E_PLAN_004("E-PLAN-004", HttpStatus.CONFLICT),

    // 2.5 외부 연동 (E-EXT) — BE-1
    E_EXT_001("E-EXT-001", HttpStatus.BAD_GATEWAY),
    /**
     * 제공자가 자격증명을 거부 — 애플 CalDAV Basic 인증 실패(ID·앱 암호 오류).
     *
     * <p><b>E-EXT-001(502)과 나누는 이유가 형식이 아니다.</b> 502 는 "제공자가 죽었으니 재시도하라"는
     * 뜻이고 사용자가 할 수 있는 일이 없다. 앱 암호 오타는 <b>사용자가 고칠 수 있는 입력 오류</b>라
     * 화면이 재시도 버튼이 아니라 입력 폼을 다시 보여줘야 한다. 둘을 한 코드로 묶으면 오타를 낸
     * 사용자가 "애플 장애"라는 안내를 보고 기다리게 된다.
     */
    E_EXT_002("E-EXT-002", HttpStatus.UNPROCESSABLE_ENTITY),
    E_EXT_004("E-EXT-004", HttpStatus.CONFLICT);

    private final String code;
    private final HttpStatus status;

    ErrorCode(String code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }
}
