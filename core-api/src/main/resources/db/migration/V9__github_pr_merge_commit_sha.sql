-- 머지 커밋 sha: push(머지) 로 돈 CI 의 check_suite 는 pull_requests 가 비어 오므로 이 값으로 PR 을 찾는다.
-- head 커밋에 검사가 없는 머지된 PR 은 이 커밋의 검사 결과를 "머지 후 CI" 로 보여 준다.
-- 기존 행은 비어 있음 — 다음 PR 조회(웹훅·연결) 때 채워지고, 그 전엔 check_suite 처리기가 GitHub 에 커밋→PR 을 물어 찾는다.
ALTER TABLE github_pull_request ADD COLUMN merge_commit_sha VARCHAR(40);
CREATE INDEX ix_github_pr_merge_commit ON github_pull_request (repo_id, merge_commit_sha) WHERE merge_commit_sha IS NOT NULL;
