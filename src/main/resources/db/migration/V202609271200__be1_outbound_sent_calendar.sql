-- 이슈 #69 · #85 리뷰 Blocking — 내보낸 일정이 «어느 캘린더에 있는지» 를 기록한다.
--
-- 대상 캘린더를 사용자가 바꿀 수 있게 되자(setWriteCalendar) 기존 설계의 공백이 드러났다:
-- 매핑 테이블 어디에도 그 이벤트가 실재하는 캘린더가 없어서, 대상을 A→B 로 바꾼 뒤 예전 일정을
-- 고치면 B 를 향해 UPDATE 를 보내고 404 로 영구 실패한다(이벤트는 A 에 있다).
--
-- NULL 은 «모름» 이다 — 이 컬럼이 생기기 전에 내보낸 행이 그렇고, 그 경우에는 적재 시점의
-- 설정값(payload) 으로 폴백한다. 그래서 기존 데이터를 백필하지 않는다(지어낼 근거가 없다).
ALTER TABLE schedule_external_refs
    ADD COLUMN sent_calendar_id VARCHAR(512);
COMMENT ON COLUMN schedule_external_refs.sent_calendar_id IS
    '이 일정이 실제로 들어 있는 외부 캘린더. 대상 설정이 바뀌어도 수정·삭제는 여기로 나간다. NULL=모름(payload 폴백)';

ALTER TABLE fixed_schedule_occurrences
    ADD COLUMN sent_calendar_id VARCHAR(512);
COMMENT ON COLUMN fixed_schedule_occurrences.sent_calendar_id IS
    '이 회차가 실제로 들어 있는 외부 캘린더. NULL=모름(payload 폴백)';

ALTER TABLE plan_block_external_refs
    ADD COLUMN sent_calendar_id VARCHAR(512);
COMMENT ON COLUMN plan_block_external_refs.sent_calendar_id IS
    '이 블록이 실제로 들어 있는 외부 캘린더. NULL=모름(payload 폴백)';
