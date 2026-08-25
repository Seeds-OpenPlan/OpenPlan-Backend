-- ST-B2-14 AC② — 수행 이력 기록 시 "당시" 예상 시간을 스냅샷으로 보존한다.
--
-- 왜 필요한가: 이 컬럼이 없으면 편차(estimated vs actual)를 계산할 때 tasks.estimated_minutes 를
-- 참조할 수밖에 없는데, 그 값은 사용자가 태스크를 편집하면 바뀐다. 그러면 이미 끝난 과거 수행의
-- 편차 수치가 소급해서 흔들린다 — SS-10 편차 분석·SS-11 보정 제안의 입력이 통째로 불안정해진다.
-- AC② 원문: "기록 시 스냅샷으로 당시 estimated_minutes 보존(이후 태스크 편집에 불변 — 편차 계산 안정성)".
--
-- 경위: data-model-erd §1 과 ST-B2-14 '데이터' 절이 execution_logs 에 estimated_minutes 를 명시했으나
-- V1__baseline.sql 이 이를 누락했고, PLAN-15 구현(feat/task-execution-logs)이 그대로 물려받았다.
--
-- NULL 허용: 태스크에 예상 시간이 없을 수 있다(tasks.estimated_minutes 도 nullable). 기존 행은
-- 기록 당시 값을 복원할 방법이 없으므로 NULL 로 남긴다 — 없는 값을 현재 값으로 채우면 "스냅샷"이라는
-- 이 컬럼의 의미 자체가 거짓이 된다. 소비처(통계)는 NULL 을 편차 계산에서 제외해야 한다.
--
-- 5분 배수 제약을 걸지 않는 이유: 이 값은 사용자 입력이 아니라 기록 시점 복사본이다. tasks 쪽 규칙이
-- 나중에 바뀌어도 과거 스냅샷은 그대로 남아야 하며, 제약을 걸면 과거 데이터가 소급 위반이 된다.
ALTER TABLE execution_logs
    ADD COLUMN estimated_minutes INTEGER;

COMMENT ON COLUMN execution_logs.estimated_minutes IS
    'ST-B2-14 AC② 기록 시점 tasks.estimated_minutes 스냅샷 (이후 태스크 편집에 불변). NULL = 당시 예상 시간 없음 또는 컬럼 신설 이전 기록';
