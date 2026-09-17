-- =====================================================================================
-- 이슈 #69 4단계 — 고정 일정을 회차로 펼쳐 내보낸다 (BE-1)
--
-- 고정 일정은 «매주 화요일 09:00~10:30» 같은 **주간 반복 패턴**이다. 밖으로 내보내려면
-- 반복 규칙(RRULE)으로 접거나, 회차로 펼쳐 개별 일정으로 넣어야 한다.
--
-- 🔴 펼치기로 했다(계획 D1). 이유는 셋이다.
--    · 주차 예외(PLAN-33)가 EXDATE 편집이 아니라 «그 일정 하나 삭제» 가 된다.
--    · 애플 CalDAV 에서 가장 위험한 연산(반복 일정 수정 → .ics 전체 덮어쓰기)을 피한다.
--    · 개인 일정·태스크 블록과 **같은 코드**로 나간다 — 전부 "시작·끝이 있는 일정" 이다.
--
-- 이 표가 그 회차 하나하나를 외부 일정과 잇는다. 사용자가 고정 일정을 고치면 회차 목록이
-- 바뀌고, 그 차이가 곧 밖으로 보낼 생성·수정·삭제다.
-- =====================================================================================
CREATE TABLE fixed_schedule_occurrences (
    occurrence_id     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    fixed_schedule_id UUID         NOT NULL REFERENCES fixed_schedules (fixed_schedule_id) ON DELETE CASCADE,
    user_id           UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    connection_id     UUID         NOT NULL REFERENCES external_calendar_connections (connection_id) ON DELETE CASCADE,
    -- 이 회차가 열리는 날. (고정일정, 날짜)가 회차의 안정적인 이름이다 — 시각이 바뀌어도 같은 회차다.
    occurrence_date   DATE         NOT NULL,
    external_uid      VARCHAR(255) NOT NULL,
    external_event_id VARCHAR(512),
    resource_href     VARCHAR(1024),
    -- 🔴 다음 쓰기의 If-Match 재료. 없으면 수정·삭제를 하지 않는다.
    etag              VARCHAR(255),
    -- 내보낸 시점의 값. 이것과 달라졌으면 사용자가 외부에서 고친 것이다.
    sent_title        VARCHAR(255),
    sent_start_at     TIMESTAMPTZ,
    sent_end_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 같은 고정 일정의 같은 날짜가 둘일 수 없다 — 중복 내보내기를 구조로 막는다.
    CONSTRAINT ux_fixed_occurrence UNIQUE (fixed_schedule_id, occurrence_date),
    CONSTRAINT ux_fixed_occurrence_uid UNIQUE (external_uid)
);

CREATE INDEX ix_fixed_occurrence_user ON fixed_schedule_occurrences (user_id, occurrence_date);

COMMENT ON TABLE fixed_schedule_occurrences IS
    '고정 일정을 2개월치 회차로 펼친 것과 외부 일정의 매핑(#69). 반복 규칙 대신 개별 일정으로 내보내는 근거 표.';
COMMENT ON COLUMN fixed_schedule_occurrences.occurrence_date IS
    '회차가 열리는 날. (고정일정, 날짜)가 회차의 이름이다 — 시각만 바뀌면 같은 회차를 수정한다.';
