-- =============================================================================
-- V4: GitHub 연동 P0 기반 — 설치(workspace↔installation), 설치 레포, 웹훅 inbox.
--
-- Case ↔ Issue/PR/CI 근거 테이블(case_github_ref 등)은 P1 에서 추가한다.
-- 설계: Downloads/error-archive-github-case-evidence-design.md §3-1, §3-6
-- =============================================================================

-- 설치 1건 = GitHub installation id(불변). 연결 주체(사용자)·범위(워크스페이스, NULL=개인).
CREATE TABLE github_installation (
    installation_id      BIGINT       PRIMARY KEY,
    account_login        VARCHAR(100) NOT NULL,
    account_type         VARCHAR(20)  NOT NULL,
    workspace_id         BIGINT,
    installed_by_user_id BIGINT       NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    created_at           TIMESTAMP(6) NOT NULL,
    updated_at           TIMESTAMP(6) NOT NULL
);
CREATE INDEX ix_github_installation_user      ON github_installation (installed_by_user_id);
CREATE INDEX ix_github_installation_workspace ON github_installation (workspace_id);

-- 설치 대상 레포. repo_id(GitHub id) 기준 — full_name 은 rename/transfer 로 바뀜.
-- is_private: 근거 노출 정책의 기준. source_updated_at: 늦게 온 옛 웹훅이 최신 값을 덮지 않도록.
CREATE TABLE github_repository (
    repo_id           BIGINT       PRIMARY KEY,
    installation_id   BIGINT       NOT NULL REFERENCES github_installation (installation_id),
    full_name         VARCHAR(200) NOT NULL,
    is_private        BOOLEAN      NOT NULL,
    removed_at        TIMESTAMPTZ,
    source_updated_at TIMESTAMPTZ
);
CREATE INDEX ix_github_repository_installation ON github_repository (installation_id);

-- 웹훅 받은 편지함 — core_outbox_event 와 같은 모양(status·attempts·next_retry_at).
-- delivery_id(X-GitHub-Delivery) PK 충돌 = 중복 전달 → 무시(멱등).
CREATE TABLE github_webhook_inbox (
    delivery_id     VARCHAR(64)  PRIMARY KEY,
    event           VARCHAR(40)  NOT NULL,
    action          VARCHAR(40),
    installation_id BIGINT,
    payload         JSONB        NOT NULL,
    status          VARCHAR(12)  NOT NULL,
    attempts        INTEGER      NOT NULL,
    last_error      TEXT,
    received_at     TIMESTAMPTZ  NOT NULL,
    processed_at    TIMESTAMPTZ,
    next_retry_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_github_webhook_inbox_pending ON github_webhook_inbox (status, next_retry_at);
