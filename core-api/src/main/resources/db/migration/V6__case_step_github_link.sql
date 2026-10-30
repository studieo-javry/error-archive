-- Step ↔ GitHub 근거 꼬리표. Case 단위 연결(case_github_link)이 원본이고, 이 표는 "이 근거가 어느 Step 것인가"만 담는다.
-- 내용(제목·CI)은 복사하지 않는다 — 비공개 가림은 근거 조회 시점에 계산.
CREATE TABLE case_step_github_link (
    step_id            BIGINT      NOT NULL,
    link_id            BIGINT      NOT NULL,     -- case_github_link.id
    case_id            BIGINT      NOT NULL,     -- Case 단위 조회·삭제용
    source             VARCHAR(20) NOT NULL,     -- MANUAL | SUGGESTION
    created_by_user_id BIGINT,
    created_at         TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (step_id, link_id)
);
CREATE INDEX ix_case_step_github_link_case ON case_step_github_link (case_id);
CREATE INDEX ix_case_step_github_link_link ON case_step_github_link (link_id);
