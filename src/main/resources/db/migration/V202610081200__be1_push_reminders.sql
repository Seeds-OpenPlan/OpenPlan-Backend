-- ADR-0015 — 시작 10분 전 푸시 알림. 신규 도메인 3테이블(push 네임스페이스), DB 변경 전부
-- 이 파일 하나로 끝난다(알림 기존 5유형·notification_settings 는 손대지 않는다 — ADR-0014 유지).
--
-- 설계 요지(ADR-0015 결정 ⑥·③·④):
--   · push_settings  — 사용자 × 토글 3종. 행이 없으면 **전부 꺼짐**으로 읽는다(기본값과 동일 — 지연 시드 불필요,
--     notification_settings 처럼 NOT NULL FK 를 충족시킬 이유가 없다).
--   · push_subscriptions — 기기 1대 = 1행(endpoint UNIQUE). 기기를 바꾸면 새 행, 구독 해지(404/410)는 행 삭제.
--   · push_deliveries — "한 번만 보낸다"의 유일한 메커니즘. UNIQUE(user_id,target_type,target_id,start_at)에
--     INSERT 하고 충돌하면 보내지 않는다 — 발송 성공/실패와 무관하게 **보내기 전에 미리 찍는다**(결정 ③).

CREATE TABLE push_settings (
    user_id                 UUID        PRIMARY KEY REFERENCES users (user_id) ON DELETE CASCADE,
    task_enabled            BOOLEAN     NOT NULL DEFAULT false,
    fixed_schedule_enabled  BOOLEAN     NOT NULL DEFAULT false,
    schedule_enabled        BOOLEAN     NOT NULL DEFAULT false,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE push_subscriptions (
    push_subscription_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    -- 브라우저 푸시 서비스가 발급하는 기기 전용 주소. 재구독(권한 재허용 등)으로 같은 기기가 다른
    -- endpoint 를 받으면 자연히 새 행이 된다 — 옛 행은 404/410 응답을 받을 때 발송기가 지운다(결정 ④).
    endpoint             VARCHAR(2048) NOT NULL UNIQUE,
    p256dh               VARCHAR(255)  NOT NULL,
    auth                 VARCHAR(255)  NOT NULL,
    -- 안드로이드 앱 전용(사용자 결정) — 서버가 신고값을 그대로 저장한다. FE 가 앱 판별 때만 구독을
    -- 만들므로 사실상 앱 전용이지만, 신고값이라 완전한 차단은 아니다(ADR-0015 결정 ④ 명시).
    platform             VARCHAR(20)   NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_success_at      TIMESTAMPTZ,
    CONSTRAINT ck_push_sub_platform CHECK (platform IN ('ANDROID_APP'))
);
-- 발송기가 "이 사용자에게 구독이 있는가"를 매 회차 묻는다(결정 ② 대상 쿼리의 EXISTS 서브쿼리).
CREATE INDEX ix_push_subscriptions_user ON push_subscriptions (user_id);

CREATE TABLE push_deliveries (
    push_delivery_id UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    target_type      VARCHAR(20)  NOT NULL,
    target_id        UUID         NOT NULL,
    start_at         TIMESTAMPTZ  NOT NULL,
    sent_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_push_delivery_target_type CHECK (target_type IN ('TASK', 'SCHEDULE', 'FIXED_SCHEDULE')),
    -- 결정 ③의 전부: 같은 대상·같은 시작은 한 번. 시작을 옮기면(새 start_at) 다시 알린다.
    CONSTRAINT ux_push_delivery UNIQUE (user_id, target_type, target_id, start_at)
);
