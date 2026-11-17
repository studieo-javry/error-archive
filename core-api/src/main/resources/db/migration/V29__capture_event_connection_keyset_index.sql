-- 이벤트 후보 조회(capture_list_events)를 **연결(connection) 스코프**로 좁히고 keyset(cursor) 페이지네이션을
-- 지원하는 인덱스. 조회는 (owner_user_id, connection_id, status) 필터 + `id DESC` 정렬 + (id < :cursor) + LIMIT.
-- 기존 ix_capture_event_owner_status(owner_user_id, status) 는 연결 필터·정렬을 못 덮음.
-- forward-only(down 없음). 정렬 인덱스는 엔티티 @Table 에 넣지 않음 → ddl-auto=validate 는 컬럼만 검사.
CREATE INDEX ix_capture_event_owner_conn_status_id
    ON capture_event (owner_user_id, connection_id, status, id DESC);
