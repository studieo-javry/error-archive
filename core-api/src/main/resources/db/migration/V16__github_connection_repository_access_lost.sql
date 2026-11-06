-- 연결별 레포 선택에 상태 추가.
--  ACTIVE      : 정상 사용 중인 선택.
--  ACCESS_LOST : GitHub 접근(grant/레포)이 사라진 선택 — 이력은 보존하되 실효 사용하지 않는다.
-- 재grant 되어도 ACCESS_LOST 는 ACTIVE 로 자동 전환하지 않는다. 사용자가 공간에서 다시 명시 선택해야 ACTIVE 가 된다
-- (selected join 만 남기고 repo.isActive 로만 거르면 재허용 시 자동 복귀하는 문제를 막기 위함).
-- forward-only. 기존 행은 전부 ACTIVE.
ALTER TABLE github_connection_repository
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
