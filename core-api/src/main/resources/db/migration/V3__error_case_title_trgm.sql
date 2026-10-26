-- =============================================================================
-- 유사 에러 추천(제목 입력 중 실시간) — pg_trgm 기반 제목 유사도.
-- word_similarity(q, title) 로 "입력한 짧은 제목"이 기존 제목의 일부와 얼마나 닮았는지 측정.
-- GIN(gin_trgm_ops) 인덱스는 제목 부분일치/유사 검색(이 기능 + 기존 q ILIKE 목록검색)을 가속.
-- (Postgres 전용 — test 는 Flyway off + H2 ddl-auto 라 이 스크립트가 실행되지 않음)
-- =============================================================================
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_error_case_title_trgm
    ON error_case USING gin (title gin_trgm_ops);

-- 본인 스코프(workspaceId 미지정) 유사 검색 보험. 싼 owner 필터로 word_similarity 계산 대상을
-- 본인 케이스로 먼저 좁혀, 본인 케이스가 많은 사용자에서도 지연을 안정화(실측 10.6ms→6.7ms).
CREATE INDEX IF NOT EXISTS idx_error_case_owner
    ON error_case (owner_user_id);
