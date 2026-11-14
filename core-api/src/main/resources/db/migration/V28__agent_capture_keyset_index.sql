-- 자동기록 초안 목록(검토 큐·검토 이력)의 keyset(cursor) 페이지네이션 지원 인덱스.
-- 조회는 (owner_user_id, status[, ...]) 필터 + `updated_at DESC, id DESC` 정렬 + LIMIT 이다.
-- 기존 ix_agent_capture_owner_status(owner_user_id, status) 는 필터만 커버 → 정렬 컬럼을 덧붙여
-- 검토 이력(IMPORTED/DISCARDED)이 단조 증가해도 keyset 스캔이 LIMIT 만큼만 읽도록 한다.
-- forward-only(down 없음). 부분/정렬 인덱스는 엔티티 @Table 어노테이션에 넣지 않음 → ddl-auto=validate 는 컬럼만 검사.
CREATE INDEX ix_agent_capture_owner_status_updated
    ON agent_capture (owner_user_id, status, updated_at DESC, id DESC);
