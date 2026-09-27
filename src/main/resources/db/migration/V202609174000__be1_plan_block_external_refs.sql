-- =====================================================================================
-- 이슈 #69 5단계 — 태스크 블록 ↔ 외부 일정 매핑 (BE-1)
--
-- 🔴 키가 plan_block_id 가 **아니다.** 자동 배치는 블록을 지웠다 새 UUID 로 다시 만들고
--    (PlanBlockService.applyBatch 가 createBlock·deleteBlock 을 쓴다), 주차 이동은
--    weekly_plan_id 만 바꾼다(PlanBlockRepository.reschedule). 둘 다 불안정하다.
--
--    (주간계획, 태스크, 순번)이 재배치를 견딘다. 한 태스크가 한 주에 여러 블록으로 쪼개질 수
--    있어 순번이 필요하다.
--
-- 🔴 external_uid 는 파생하지 않는다 — 발급받아 이 행이 들고 다닌다(OpenPlanEventUid 주석).
--    파생할 안정적인 값이 애초에 없어서다. 블록이 다른 주로 옮겨 가면 이 행의 weekly_plan_id
--    만 바뀌고 UID 는 그대로라, 외부에는 **수정 한 번**이 나간다.
-- =====================================================================================
CREATE TABLE plan_block_external_refs (
    ref_id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    connection_id     UUID         NOT NULL REFERENCES external_calendar_connections (connection_id) ON DELETE CASCADE,
    weekly_plan_id    UUID         NOT NULL REFERENCES weekly_plans (weekly_plan_id) ON DELETE CASCADE,
    task_id           UUID         NOT NULL REFERENCES tasks (task_id) ON DELETE CASCADE,
    -- 한 태스크가 한 주에 여러 번 배치될 수 있다. 0부터.
    sequence          INTEGER      NOT NULL,
    external_uid      VARCHAR(255) NOT NULL,
    external_event_id VARCHAR(512),
    resource_href     VARCHAR(1024),
    etag              VARCHAR(255),
    sent_title        VARCHAR(255),
    sent_start_at     TIMESTAMPTZ,
    sent_end_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ux_plan_block_ref UNIQUE (weekly_plan_id, task_id, sequence),
    CONSTRAINT ux_plan_block_ref_uid UNIQUE (external_uid)
);

CREATE INDEX ix_plan_block_ref_plan ON plan_block_external_refs (weekly_plan_id);

COMMENT ON TABLE plan_block_external_refs IS
    '태스크 블록과 외부 일정의 매핑(#69). 키가 plan_block_id 가 아닌 이유는 파일 머리 주석 참조.';
COMMENT ON COLUMN plan_block_external_refs.external_uid IS
    '발급받은 UID(파생 아님). 블록이 재생성되거나 다른 주로 옮겨 가도 이 값은 그대로라 외부에는 수정이 나간다.';
