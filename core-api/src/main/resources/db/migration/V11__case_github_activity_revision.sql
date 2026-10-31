-- Case 별 GitHub 활동 리비전 — 활동 줄이 새로 생기거나 내용이 바뀔 때마다 +1.
-- 건수(totalCount)로는 CI 집계 덮어쓰기(id·건수 그대로)를 알 수 없어서 둔다. 행이 없으면 0.
CREATE TABLE case_github_activity_revision (
    case_id    BIGINT      PRIMARY KEY,
    revision   BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
