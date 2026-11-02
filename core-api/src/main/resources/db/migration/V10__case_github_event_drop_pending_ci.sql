-- CI 진행 중(PENDING) 은 타임라인에 남기지 않는다(최종 통과/실패만). 예전 규칙으로 남은 진행 중 줄은 지운다 —
-- 같은 키(CI:{repo}:{pr}:{sha})로 최종 결과가 오면 새 줄로 다시 생긴다. 카드의 CI 요약(github_pull_request)은 그대로.
DELETE FROM case_github_event
 WHERE type = 'CI_COMPLETED'
   AND data ->> 'conclusion' = 'PENDING';
