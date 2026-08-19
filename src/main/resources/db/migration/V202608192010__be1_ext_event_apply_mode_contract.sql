-- =====================================================================================
-- ST-B1-11 — external_calendar_events.apply_mode 를 openapi 정본에 맞춘다 (BE-1)
--
-- 배경: V1 baseline 의 CHECK 와 openapi 의 요청 enum 이 **3값 중 2값에서 어긋나 있었다**.
--
--         openapi  mode: enum [AS_IS, EDITED,   EXCLUDE ]   ← 정본 (L705)
--         baseline CHECK       ('AS_IS','MODIFIED','EXCLUDED')
--
--       요청 값을 그대로 저장하면 EDITED·EXCLUDE 가 CHECK 위반으로 500 이 된다.
--       AS_IS 하나만 우연히 통과한다 — 즉 8 EP 중 ONB-09 반영이 2/3 확률로 죽는다.
--
-- 방향: **openapi 가 정본이므로 DB 를 맞춘다**(source_of_truth). 반대로 계약을 DB 에
--       맞추면 이미 확정된 ONB-09 계약과 FE 구현 기준이 흔들린다.
--
-- apply_status 는 건드리지 않는다: 양쪽 모두 (CANDIDATE, APPLIED, EXCLUDED) 로 일치한다.
--       이 마이그레이션 후 같은 테이블에 apply_mode='EXCLUDE' 와 apply_status='EXCLUDED' 가
--       공존하는데, 의도된 것이다 — 앞은 **사용자가 고른 행동**이고 뒤는 **그 결과 상태**다.
--
-- 데이터 이관이 없는 근거: 이 테이블에 쓰는 코드가 아직 0건이다(엔티티·리포지토리·컨트롤러
--       전부 미착수 — 착수 전 git grep 실측). 따라서 기존 행 변환이 필요 없다.
-- =====================================================================================

ALTER TABLE external_calendar_events
    DROP CONSTRAINT ck_ext_event_apply_mode;

ALTER TABLE external_calendar_events
    ADD CONSTRAINT ck_ext_event_apply_mode
        CHECK (apply_mode IS NULL OR apply_mode IN ('AS_IS', 'EDITED', 'EXCLUDE'));

COMMENT ON COLUMN external_calendar_events.apply_mode IS
    'ONB-09 반영 방식 — openapi applyExternalEvent 의 mode 와 같은 값 (AS_IS/EDITED/EXCLUDE). NULL 은 아직 미반영(CANDIDATE).';
