-- Agent Capture (P0) PR4 — 검토 반영(import) 출처 컬럼.
--
-- 봉인된 초안을 사용자가 검토·확정하면 신규 ErrorCase 로 반영된다. 그 케이스가 "직접 작성"이 아니라
-- "에이전트 초안 반영"임을 표시하고(source), 검토자(reviewed_by_user_id)와 출처 초안(source_capture_id)을
-- 추적한다. 잔디/KPI 제외는 발행 이벤트의 origin(AGENT_IMPORT) 게이트로 처리하고, 여기 컬럼은 출처 보존용.
--
-- 기존 모든 작성 경로는 손대지 않는다: source 는 DEFAULT 'MANUAL' NOT NULL 이라 기존 행·기존 INSERT 가
-- 그대로 MANUAL 로 남는다. 엔티티(ErrorCaseEntity, AgentCaptureEntity)와 정합(validate).

ALTER TABLE error_case
    ADD COLUMN source              VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN reviewed_by_user_id BIGINT,
    ADD COLUMN source_capture_id   BIGINT;

-- agent_capture: 반영 성공 시 생성된 케이스 id (IMPORTED 상태에서만 non-null).
ALTER TABLE agent_capture
    ADD COLUMN imported_case_id BIGINT;
