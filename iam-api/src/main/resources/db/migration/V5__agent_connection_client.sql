-- Agent Capture — 연결 식별 개선(#148 후속): 설치 환경 메타를 연결에 기록해 목록을 사람이 알아보게 한다.
--
-- 토큰이 자격증명(진실)이라는 점은 그대로다. 아래 컬럼은 **표시용 메타**로, 커넥터가 첫 사용(first-use)에
-- 신고하는 값이다(클라이언트 자기신고 → 위조 가능 → 인가/스코프 판단엔 절대 쓰지 않는다).
--
-- 전부 nullable 추가 = 후방호환. 설치 정보를 안 보내는 구형 커넥터는 지금과 동일하게 동작한다.
-- 엔티티 AgentConnectionEntity 와 정확히 일치해야 한다(stg/prod ddl-auto=validate).

ALTER TABLE iam_agent_connection
    ADD COLUMN client_kind     VARCHAR(40),   -- 커넥터 종류(예: 'claude-code')
    ADD COLUMN client_version  VARCHAR(40),   -- 커넥터 버전
    ADD COLUMN install_id      VARCHAR(64),   -- 설치당 1회 랜덤 UUID(하드웨어 지문 아님). 표시·그룹용
    ADD COLUMN install_label   VARCHAR(120),  -- 사람이 읽는 설치 이름(기본 hostname)
    ADD COLUMN client_bound_at TIMESTAMPTZ;   -- 설치 정보 최초 기록 시각(first-use 1회 바인딩)

-- (owner, install_id) 로 같은 설치의 연결을 모으기 위한 부분 인덱스. 활성·설치식별 가능한 연결만.
-- 부분 인덱스는 엔티티 @Table 에 넣지 않는다(ddl-auto=validate 는 컬럼/타입만 검사).
CREATE INDEX ix_iam_agent_connection_install
    ON iam_agent_connection (owner_user_id, install_id)
    WHERE install_id IS NOT NULL AND revoked_at IS NULL;
