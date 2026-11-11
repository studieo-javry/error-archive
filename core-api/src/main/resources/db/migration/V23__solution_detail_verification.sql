-- error_case_solution 에 상세 설명(description) + 검증 근거(verification) 보존 컬럼 추가.
-- agentcapture 초안 반영(import) 시 후보 Solution 의 description/verification 을 그대로 이관하기 위함(#144).
-- 기존 행은 둘 다 NULL (하위호환). forward-only — down 없음.
ALTER TABLE error_case_solution
    ADD COLUMN description  TEXT,
    ADD COLUMN verification JSONB;
