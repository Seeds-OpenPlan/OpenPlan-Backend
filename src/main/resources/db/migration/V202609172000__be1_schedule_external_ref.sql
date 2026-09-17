-- =====================================================================================
-- 이슈 #69 3단계 — 개인 일정 ↔ 외부 일정 매핑 (BE-1)
--
-- 되받기(#69 D4)는 «내보낼 때 무엇이었나» 와 «지금 무엇인가» 의 차이로만 판정된다.
-- 그 «무엇이었나» 를 여기 둔다. 이 값이 없으면 D4 가 성립하지 않는다.
--
-- schedules 에 컬럼을 늘리지 않고 별도 표로 둔 이유 — 일곱 개가 붙고, 전부 외부 연동을
-- 쓸 때만 의미가 있다. 연동 안 쓰는 사용자의 모든 일정 행에 NULL 일곱 개를 달지 않는다.
-- =====================================================================================
CREATE TABLE schedule_external_refs (
    schedule_id       UUID         PRIMARY KEY REFERENCES schedules (schedule_id) ON DELETE CASCADE,
    connection_id     UUID         NOT NULL REFERENCES external_calendar_connections (connection_id) ON DELETE CASCADE,
    -- 우리가 발급한 UID. 에코 차단의 근거이자 애플 리소스 주소의 재료다(OpenPlanEventUid).
    external_uid      VARCHAR(255) NOT NULL,
    -- 제공자가 준 참조. 구글은 event_id, 애플은 resource_href 로 주소를 만든다.
    external_event_id VARCHAR(512),
    resource_href     VARCHAR(1024),
    -- 🔴 다음 쓰기의 If-Match 재료. 없으면 수정·삭제를 하지 않는다 — 남의 변경을 덮지 않기 위해.
    etag              VARCHAR(255),
    -- 내보낸 시점의 값(스냅샷). 다음 조회에서 이것과 달라졌으면 **사용자가 외부에서 고친 것**이다.
    sent_title        VARCHAR(200),
    sent_start_at     TIMESTAMPTZ,
    sent_end_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_schedule_external_uid UNIQUE (external_uid)
);

CREATE INDEX ix_schedule_ext_connection ON schedule_external_refs (connection_id);

COMMENT ON TABLE schedule_external_refs IS
    '개인 일정과 외부 캘린더 일정의 1:1 매핑(#69). 내보낸 시점의 값을 함께 보관해 외부 편집을 가려낸다.';
COMMENT ON COLUMN schedule_external_refs.sent_title IS
    '내보낼 때의 제목. 다음 조회에서 이것과 다르면 사용자가 외부에서 고친 것이다(되받기의 판정 기준).';
