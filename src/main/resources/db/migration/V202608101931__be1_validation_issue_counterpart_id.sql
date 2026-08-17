-- =====================================================================================
-- ADR-0013 — validation_issues.counterpart_id (BE-1 작성 · BE-2 ST-B2-09 가 소비)
--
-- 배경: ADR-0013(쌍 규칙 이슈 의미론)이 counterpartId 를 계약 전 계층에 반영했는데
--       **DB 컬럼만 빠져 있었다**:
--         · rule-engine-contract.md   ✅ (55ff2ea)
--         · ValidationIssue 자바 레코드 ✅ (PR #18)
--         · openapi.yaml               ✅ (PR #19)
--         · validation_issues 테이블    ❌  ← 이 파일이 메운다
--
--       ST-B2-09(검증 실행)가 판정 결과를 영속하는 순간, 쌍 규칙(V1 겹침 · V2 고정일정 충돌)의
--       **상대 엔티티가 유실**된다. (plan_block_id, counterpart_id) 조합이 쌍을 유일 식별하는
--       설계인데 절반만 남으면 "무엇과 겹쳤는지"를 복원할 수 없다. 조용히 깨지는 유형이라
--       테스트도 잡지 못한다 — 저장은 성공하고 값만 사라진다.
--
-- FK 를 걸지 않는 이유: 가리키는 대상이 규칙마다 다르다.
--       V1_OVERLAP        → 상대 plan_block (UUID 큰 쪽)
--       V2_FIXED_CONFLICT → fixed_schedule
--       그 외 규칙        → NULL
--       한 컬럼이 두 테이블을 가리키므로 참조 무결성을 DB 제약으로 표현할 수 없다.
--       대신 부모(weekly_plan)가 지워지면 이슈 행 자체가 CASCADE 로 사라져 고아가 남지 않는다.
--
-- nullable 인 근거: 쌍이 아닌 규칙(V3~V6)에서는 상대가 존재하지 않는다 — 값 없음이 정상이다.
-- =====================================================================================

ALTER TABLE validation_issues
    ADD COLUMN counterpart_id UUID;

COMMENT ON COLUMN validation_issues.counterpart_id IS
    '쌍 규칙의 상대 엔티티 — V1: 상대 plan_block, V2: fixed_schedule. 그 외 NULL. FK 없음(대상 테이블이 규칙별로 다름). ADR-0013';
