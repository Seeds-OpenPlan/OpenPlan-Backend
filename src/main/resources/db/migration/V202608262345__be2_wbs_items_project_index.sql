-- PROJ-11/12/13 · wbs_items(project_id) 인덱스 — 프로젝트 스코프 조회 3경로가 풀스캔이었다.
--
-- baseline 의 wbs_items 는 task_id 에만 UNIQUE(→ 암묵 인덱스)가 있고 project_id 는 맨몸이다.
-- Postgres 는 FK 컬럼을 자동 인덱싱하지 않는다. 바로 위 tasks 가 같은 목적으로
-- ix_tasks_project_status·ix_tasks_project_due 두 개를 갖고 있는 것과 대비된다 — 빠뜨린 자리다.
--
-- 이번에 project_id 로 읽는 경로가 셋 늘었다:
--   · WbsItemRepository.findViewsByProjectId  — WBS 뷰 (PROJ-13, 태스크 제목 조인)
--   · WbsItemRepository.countByProjectId      — 복제 프리뷰 항목 수 (PROJ-11)
--   · WbsItemRepository.findByProjectId       — 복제 실행 시 원본 WBS 전량 (PROJ-12)
-- 전부 단일 프로젝트 스코프인데 테이블 전체를 훑고 있었다. 사용자·프로젝트가 늘수록 선형으로 나빠진다.
--
-- 커버링까지 갈 이유는 없다. 한 프로젝트의 WBS 행 수는 태스크 수와 같아(1:0..1) 수십 건 규모이고,
-- 뷰 조회는 어차피 tasks 조인이 필요해 인덱스만으로 끝나지 않는다. project_id 단일 컬럼이면 충분하다.

CREATE INDEX ix_wbs_items_project ON wbs_items (project_id);
