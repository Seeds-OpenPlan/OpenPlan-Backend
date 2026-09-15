-- =====================================================================================
-- ST-B1-11 / 이슈 #69 — 외부 캘린더에 **쓰기** 위해 필요한 참조를 저장한다 (BE-1)
--
-- 지금까지는 읽기만 했으므로 "무엇을 가져왔나" 만 있으면 됐다. 밖으로 쓰려면
-- "그것이 제공자 어디에 있는가" 를 알아야 하는데, 그 정보를 전부 버리고 있었다.
--
--   🟢 external_calendar_id 는 이 마이그레이션에 없다 — V202608290150 이 이미 추가했다.
--      그 컬럼은 원래 이 PR 의 것이었으나, #70 리뷰의 Blocking(삭제 귀속을 표시 이름으로
--      판정하던 결함)을 막기 위해 그쪽으로 먼저 당겨 갔다. 여기에 다시 ADD COLUMN 하면
--      "column already exists" 로 Flyway 가 실패하고 **앱이 기동하지 못한다.**
--      쓰기 주소의 앞부분으로 쓰는 것은 그대로다 — 컬럼을 추가하지 않을 뿐이다.
--
--   resource_href         애플 .ics 리소스 주소. CalDAV 는 이 주소로 PUT/DELETE 한다.
--                         구글은 calendarId+eventId 로 주소가 정해지므로 NULL.
--   etag                  If-Match 용. 🔴 이것 없이 PUT 하면 그 사이 남이 고친 것을
--                         **말없이 덮는다.** 조회 때 이미 받고 있었는데 읽지 않고 버렸다.
--   recurring             원본이 반복 일정인가(RRULE / 구글 recurringEventId).
--                         🔴 쓰기를 막는 근거다 — 아래 참조.
--                         **NULL 을 허용한다. NULL = 아직 모름이다.**
--                         NOT NULL DEFAULT false 로 두면 안 된다 — 기존 행이 전부 "반복 아님"
--                         으로 채워지는데, 그 행들은 이미 external_calendar_id 를 갖고 있어
--                         (V202608290150, #70) isWritable() 이 곧바로 true 가 된다.
--                         실제로는 반복인지 **전혀 모르는** 행이 "쓰기 가능" 으로 판정되고,
--                         그것은 이 파일이 선언한 "모르면 쓰지 않는다" 와 정반대다(#71 리뷰).
--                         다음 동기화가 값을 채울 때까지 모름으로 남겨 쓰기에서 빠지게 한다.
--
-- 🔴 recurring 이 왜 필요한가. 읽기는 **회차 단위**인데 쓰기는 **파일 단위**다.
--    애플은 .ics 하나(UID 하나)가 여러 회차로 펼쳐지고(AppleCalDavProvider 주석 참조 —
--    external_event_id 가 `UID#시작시각` 인 이유), CalDAV 의 PUT 은 그 리소스를 통째로
--    덮어쓴다. 회차 하나만 고치려고 순진하게 PUT 하면 **반복 일정 전체가 그 값으로 덮인다.**
--    올바르게 하려면 RECURRENCE-ID 오버라이드를 같은 파일에 넣어야 하는데, 틀리면 사용자의
--    실제 캘린더가 깨진다. 그래서 당분간 **반복 일정은 쓰기 대상에서 제외**하고, 그 판정을
--    할 수 있도록 이 플래그를 남긴다. 모르면 쓰지 않는다.
--
-- 기존 행: 전부 NULL 로 시작한다(recurring 도 NULL = 모름). 다음 동기화가 값을 채운다(resync 가 갱신한다).
--    소급 채우기를 하지 않는 이유 — 그 정보는 제공자에게 다시 물어야만 알 수 있고,
--    다음 조회가 어차피 그 일을 한다.
-- =====================================================================================

ALTER TABLE external_calendar_events
    ADD COLUMN resource_href VARCHAR(1024),
    ADD COLUMN etag          VARCHAR(255),
    ADD COLUMN recurring     BOOLEAN;

COMMENT ON COLUMN external_calendar_events.resource_href IS
    '애플 CalDAV .ics 리소스 주소(PUT/DELETE 대상). 구글은 NULL.';
COMMENT ON COLUMN external_calendar_events.etag IS
    'If-Match 용. 없으면 남의 변경을 말없이 덮는다.';
COMMENT ON COLUMN external_calendar_events.recurring IS
    '원본이 반복 일정인가. NULL = 아직 모름(다음 동기화가 채운다). true 이거나 NULL 이면 밖으로 쓰지 않는다 — 회차 하나를 고치려다 전체를 덮을 수 있다.';
