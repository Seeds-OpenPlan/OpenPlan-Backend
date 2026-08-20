-- 외부 캘린더 제공자 CHECK 를 계약에 맞춘다 (ST-B1-11).
--
-- baseline 의 ck_ext_conn_provider 는 ('GOOGLE','NAVER','KAKAO') 였다. 계약이 여는 제공자가
-- 구글·애플로 정해졌으므로 APPLE 을 허용하지 않으면 연동 저장이 제약 위반으로 실패한다.
--
-- 남은 세 값을 그대로 두지 않는 이유: D-55 에서 겪은 것의 반대편이다. 그때는 CHECK 가 허용하는
-- 범위를 계약의 요구로 오독해 없는 일을 만들었다. 스키마가 계약보다 넓으면 읽는 사람이 매번
-- "이건 되는 건가"를 되묻게 되므로, 계약이 여는 값과 정확히 맞춘다.
-- 카카오·네이버 구현은 backup/external-calendar-kakao · backup/external-calendar-naver 브랜치에
-- 보존돼 있고, 되살릴 때 이 제약도 함께 되돌린다.

ALTER TABLE external_calendar_connections
    DROP CONSTRAINT IF EXISTS ck_ext_conn_provider;

ALTER TABLE external_calendar_connections
    ADD CONSTRAINT ck_ext_conn_provider CHECK (provider IN ('GOOGLE', 'APPLE'));
