-- 외부 캘린더 제공자에 APPLE 을 연다 (ST-B1-11).
--
-- baseline 의 ck_ext_conn_provider 는 ('GOOGLE','NAVER','KAKAO') 였다. 프론트가 처음부터
-- Google·Apple 로 만들어져 있는데(CalendarConnectionSection.jsx) 스키마에 APPLE 이 없어,
-- 애플 연동을 저장하는 순간 제약 위반으로 실패한다.
--
-- 계약이 여는 제공자는 GOOGLE·APPLE 둘뿐이다. 다만 CHECK 를 그 둘로 좁히지는 않는다 —
-- 이미 저장된 행이 있을 수 있고, 스키마가 담을 수 있는 범위와 계약이 요구하는 범위는
-- 다르기 때문이다(D-55). 범위 판단은 openapi 에서 한다.

ALTER TABLE external_calendar_connections
    DROP CONSTRAINT IF EXISTS ck_ext_conn_provider;

ALTER TABLE external_calendar_connections
    ADD CONSTRAINT ck_ext_conn_provider
        CHECK (provider IN ('GOOGLE', 'APPLE', 'NAVER', 'KAKAO'));
