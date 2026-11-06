-- 연결에 낙관적 락 버전 추가 — 여러 워크스페이스 관리자가 같은 연결의 레포 선택을 동시에 바꿀 때
-- 오래된 상태로 덮어쓰지 못하게 한다(JPA @Version). 선택/상태 변경 시마다 증가.
-- forward-only. 기존 행은 0 으로 시작.
ALTER TABLE github_connection
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
