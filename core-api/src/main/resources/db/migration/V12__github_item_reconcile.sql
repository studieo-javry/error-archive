-- =============================================================================
-- V12: 놓친 웹훅 재동기화 — Case 에 붙은 Issue/PR 을 마지막으로 GitHub 에 다시 확인한 시각.
-- GitHub 는 전달에 실패한 웹훅을 자동으로 다시 보내지 않는다 → 주기적으로 다시 조회해 맞춘다(GithubEvidenceReconciler).
-- 스냅샷 테이블(github_issue / github_pull_request)과 분리: 스냅샷 저장은 행 전체를 덮어써 이 값이 지워진다.
-- 행이 없으면 아직 한 번도 확인하지 않은 것(가장 먼저 확인).
-- =============================================================================
CREATE TABLE github_item_reconcile (
    kind       VARCHAR(20) NOT NULL,   -- ISSUE | PULL_REQUEST
    repo_id    BIGINT      NOT NULL,
    number     INTEGER     NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (kind, repo_id, number)
);
