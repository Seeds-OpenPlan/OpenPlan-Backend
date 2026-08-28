-- =====================================================================================
-- ST-B1-11 / 이슈 #68 — 고정 일정이 어느 외부 일정에서 왔는지를 기록한다 (BE-1)
--
-- 배경: 반영(ONB-09)은 외부 일정의 **값만 복사한 새 행**을 만들고, 만든 뒤에는 그 고정
--       일정이 어느 외부 일정에서 왔는지 알 방법이 없었다. connection_id(연동 단위)는
--       있지만 **일정 단위 링크가 없다.** 여기서 세 증상이 함께 나왔다.
--
--         ① 외부에서 일정을 고쳐도 주간 계획이 안 따라간다 (역추적 불가)
--         ② 외부에서 지워도 고정 일정이 남는다              (대상 특정 불가)
--         ③ 이중 클릭으로 고정 일정이 두 벌 생겨도 DB 가 못 막는다
--            (PR #33 리뷰 지적 — "원본 이벤트를 향한 UQ 가 없어 DB 도 막아 주지 못한다")
--
--       ①②③ 은 서로 다른 증상이지만 **원인은 이 링크 하나의 부재**다.
--
-- ON DELETE CASCADE 인 이유: 외부 일정 행이 사라지는 경로는 연동 해제(connection CASCADE)
--       뿐이고, 그때 고정 일정도 함께 사라지는 것이 이미 정해진 동작이다(FIX-17 · ST-B1-11 AC4 —
--       fixed_schedules.connection_id 가 같은 규칙을 쓴다). 두 경로가 어긋나면 한쪽만 남는
--       행이 생긴다.
--
-- 🔴 부분 UNIQUE 인 이유: MANUAL 고정 일정은 이 값이 NULL 이고, NULL 은 여러 행이 가질 수
--       있어야 한다. 일반 UNIQUE 를 걸면 Postgres 는 NULL 을 서로 다른 값으로 보므로 동작은
--       하지만, 의도를 부분 인덱스로 명시해 두는 편이 읽는 사람에게 분명하다.
--       이 제약이 ③을 DB 수준에서 닫는다 — 애플리케이션 check-then-act 는 진짜 동시 요청을
--       막지 못한다(PR #33 리뷰 Should-fix).
--
-- 기존 행: 이 컬럼은 NULL 로 시작한다. 이미 반영된 고정 일정은 출처를 되찾을 방법이 없다
--       (그 정보가 어디에도 없다). 소급 채우기를 시도하지 않는다 — 제목·시각으로 추측해
--       잘못 잇는 것보다 NULL 로 두는 편이 안전하다. NULL 인 행은 아래 ①② 대상이 아니며,
--       사용자가 다시 반영하면 그때부터 링크를 갖는다.
-- =====================================================================================

ALTER TABLE fixed_schedules
    ADD COLUMN external_calendar_event_id UUID
        REFERENCES external_calendar_events (external_calendar_event_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX ux_fixed_external_event
    ON fixed_schedules (external_calendar_event_id)
    WHERE external_calendar_event_id IS NOT NULL;

COMMENT ON COLUMN fixed_schedules.external_calendar_event_id IS
    '이 고정 일정을 만든 외부 일정(ONB-09 반영). MANUAL 은 NULL. 원격 수정·삭제를 따라가는 근거이자 중복 반영을 막는 키.';
