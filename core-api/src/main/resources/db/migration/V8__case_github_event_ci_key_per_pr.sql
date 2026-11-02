-- CI 이벤트 중복 방지 키에 PR 번호 포함: CI:{repo}:{sha} → CI:{repo}:{pr}:{sha}.
-- 같은 HEAD 커밋을 쓰는 PR 이 둘이면 옛 키는 한 줄로 합쳐져 뒤 PR 의 CI 가 앞 PR 에 붙어 보였다.
-- 기존 키는 Case 안에서 이미 유일하고 새 키는 거기에 PR 번호만 더하므로 바꾸는 중 유니크 충돌 없음.
UPDATE case_github_event
   SET dedupe_key = 'CI:' || repo_id || ':' || number || ':' || (data ->> 'sha')
 WHERE type = 'CI_COMPLETED'
   AND dedupe_key = 'CI:' || repo_id || ':' || (data ->> 'sha');
