-- =====================================================================================
-- 이슈 #69 — 밖으로 내보내기의 기반 (BE-1 · 계획 0단계)
--
-- 아직 내보내는 코드는 없다. 이 마이그레이션은 **그 코드가 서기 전에 정해져 있어야 하는
-- 두 가지**만 만든다. 나중에 붙이면 되돌릴 수 없는 것들이다.
-- =====================================================================================

-- ① 어디에 쓸 것인가
--
-- 읽기만 할 때는 없던 문제다. 사용자가 구글·애플을 둘 다 연결했고 캘린더도 여럿이면
-- "새 일정을 어느 캘린더에 만들 것인가" 를 정해야 한다.
--
-- 🔴 NULL 은 "아직 안 골랐다" 이고, 그때는 **내보내지 않는다.** 아무 캘린더나 골라 주면
--    사용자가 모르는 곳에 일정이 생긴다. 모르면 쓰지 않는다 — 이 저장소가 삭제 귀속
--    (#70 리뷰)과 반복 판정(#71 리뷰)에서 두 번 같은 결론에 닿은 원칙이다.
ALTER TABLE external_calendar_connections
    ADD COLUMN write_calendar_id VARCHAR(512);

COMMENT ON COLUMN external_calendar_connections.write_calendar_id IS
    '새 일정을 만들 대상 캘린더 식별자(구글 calendarId · 애플 캘린더 href). NULL = 아직 안 고름 → 내보내지 않는다.';


-- ② 무엇을 내보내야 하는가 (아웃박스)
--
-- 🔴 외부 호출을 사용자 요청에 묶으면 안 된다. 일정 저장이나 계획 확정이 구글 응답을
--    기다리면, 구글이 느리거나 죽었을 때 **OpenPlan 의 일정 수정이 같이 실패한다.**
--    사용자에게는 "내 앱이 고장났다" 로 보인다.
--    그래서 변경을 여기 적고 커밋한 뒤, 밀어내기는 따로 한다.
--
-- 🔴 payload 가 왜 필요한가 — **삭제 때문이다.** 주간 계획이나 태스크를 지우면
--    ON DELETE CASCADE 로 블록이 먼저 사라져, 그 시점에는 "무엇을 외부에서 지워야
--    하는지" 를 알 수 없다. 지우기 전에 필요한 값을 여기 담아 둔다.
--
-- 재시도는 별도 배치가 아니라 다음 동기화가 한다(ADR-0006 의 "조회 시 지연 평가" 와
-- 같은 자리). 이 저장소에는 @Scheduled 가 하나도 없고, 그 결정을 뒤집지 않는다.
CREATE TABLE outbound_calendar_ops (
    op_id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    connection_id UUID         REFERENCES external_calendar_connections (connection_id) ON DELETE CASCADE,
    -- 무엇을 내보내는가. 세 대상이 같은 표를 쓴다 — 전부 "시작·끝이 있는 일정" 으로 나간다.
    target_type   VARCHAR(20)  NOT NULL,
    target_id     UUID         NOT NULL,
    operation     VARCHAR(10)  NOT NULL,
    -- 보낼 때 필요한 값(제목·시각·외부 식별자). 삭제 대상은 원본이 이미 없을 수 있다.
    payload       JSONB        NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts      INTEGER      NOT NULL DEFAULT 0,
    -- 실패 사유를 남긴다. 남기지 않으면 "안 나갔다" 만 알고 왜인지 모른다.
    last_error    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_outbound_target CHECK (target_type IN ('SCHEDULE', 'FIXED_OCCURRENCE', 'PLAN_BLOCK')),
    CONSTRAINT ck_outbound_op     CHECK (operation   IN ('CREATE', 'UPDATE', 'DELETE')),
    CONSTRAINT ck_outbound_status CHECK (status      IN ('PENDING', 'DONE', 'FAILED'))
);

-- 밀어내기가 읽는 유일한 질의 — "이 사용자의 아직 안 나간 것" 을 만든 순서대로.
CREATE INDEX ix_outbound_pending ON outbound_calendar_ops (user_id, status, created_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE outbound_calendar_ops IS
    '외부 캘린더로 내보낼 변경의 대기열(#69). 사용자 요청은 여기 적고 커밋만 하며, 실제 호출과 재시도는 다음 동기화가 한다.';
COMMENT ON COLUMN outbound_calendar_ops.payload IS
    '보낼 때 필요한 값. 삭제는 원본 행이 CASCADE 로 이미 사라진 뒤에 처리되므로, 지우기 전에 여기 담아 둬야 한다.';
