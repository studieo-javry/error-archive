-- =============================================================================
-- 데모 발행물 시드 마커
--
-- `is_demo = true` 행은 데모 환경(둘러보기)에서 자동 시드한 발행물이다.
--   · 워크스페이스 데모 발행물 → owner_user_id = 예약 상수(DEMO_WORKSPACE_OWNER_ID), 정적·멱등.
--   · 게스트 개인 발행물       → owner_user_id = 그 게스트, 지연 생성·야간 purge 대상.
-- 야간 정리는 `is_demo = true AND owner_user_id <> :demoOwnerId`(게스트분)만 삭제한다.
-- 실데이터(is_demo = false)는 기존 그대로 — 이 컬럼은 데모 전용 라이프사이클 식별자다.
-- =============================================================================

ALTER TABLE case_publishment
    ADD COLUMN is_demo BOOLEAN NOT NULL DEFAULT false;

-- 야간 purge( is_demo + owner 조건 ) 가 테이블 풀스캔하지 않도록 부분 인덱스.
CREATE INDEX ix_case_publishment_demo ON case_publishment (owner_user_id) WHERE is_demo = true;
