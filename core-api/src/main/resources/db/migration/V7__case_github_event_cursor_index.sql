-- 근거 타임라인 커서 조회(최신순, occurred_at 동시각은 id 로 구분)용. 기존 (case_id, occurred_at) 를 대체.
DROP INDEX IF EXISTS ix_case_github_event_case;
CREATE INDEX ix_case_github_event_cursor ON case_github_event (case_id, occurred_at, id);
