-- FIX-10 보강 · 주간 가용 시간 목표 (오너 결정 2026-07-25)
--
-- 🔴 "요일 창의 합계" 와 다른 값이다. 합계는 availability_patterns 에서 계산되는 참고용 총량이고,
--    이것은 사용자가 직접 정하는 주간 목표다. 프론트가 두 값을 이미 구분해 쓰고 있는데
--    (availabilityHelpers.rangeSumMinutes 주석) 서버에 담을 자리가 없어 이번에 만든다.
--
-- 성격이 "동작 기본값" 이라 프로필이 아니라 user_preferences 에 둔다(ERD §7 분기 2와 같은 판단).
-- 전용 리소스(/users/me/availability-target)를 새로 만들지 않은 이유이기도 하다 —
-- 설정값 하나에 리소스를 하나 더 만들면 계약이 그만큼 넓어진다.

ALTER TABLE user_preferences
    ADD COLUMN weekly_available_minutes INTEGER;

COMMENT ON COLUMN user_preferences.weekly_available_minutes IS
    '주간 가용 시간 목표(분). NULL = 미설정. 요일 창 합계와 별개 값 — 사용자가 직접 정한다.';

-- 5분 단위·양수. 예상시간 제약(ck_user_pref_estimated)과 같은 규약을 쓴다.
ALTER TABLE user_preferences
    ADD CONSTRAINT ck_user_pref_weekly_available
        CHECK (weekly_available_minutes IS NULL
               OR (weekly_available_minutes % 5 = 0 AND weekly_available_minutes > 0));
