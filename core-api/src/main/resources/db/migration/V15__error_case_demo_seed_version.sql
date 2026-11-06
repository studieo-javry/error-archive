-- =============================================================================
-- V15: 데모 시드 케이스 표식 — demo_seed_version.
--
--   NULL      = 일반 케이스(회원·게스트가 직접 만든 것).
--   NOT NULL  = 데모 시드가 만든 케이스. 값은 그 케이스를 만든 시드 콘텐츠 버전.
--               응답의 `demo=true`("데모" 배지)와, 공개 쇼케이스 재시드 판단(버전 비교)에 쓴다.
--
-- 버전을 태그(seed-vN) 대신 컬럼에 두는 이유: 공개 쇼케이스는 회원 검색에 노출되므로
-- 카드에 내부용 버전 태그가 보이지 않게 한다.
-- 시드 경로만 bulk update 로 기록 — 일반 저장/수정은 이 컬럼을 건드리지 않는다(entity insertable/updatable=false).
-- =============================================================================
ALTER TABLE error_case ADD COLUMN demo_seed_version INTEGER;

CREATE INDEX ix_error_case_demo_seed ON error_case (id) WHERE demo_seed_version IS NOT NULL;
