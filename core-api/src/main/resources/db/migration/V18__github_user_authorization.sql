-- =====================================================================
-- V18: github_user_authorization — EA 사용자별 GitHub OAuth 사용자 토큰(암호화) 저장
--   · 시나리오 ②: 재연결 시 GitHub 재이동 없이 설치/레포를 다시 조회하기 위해 유효 토큰 재사용
--   · github_app_authorization(revoked) 웹훅: github_user_id 로 찾아 폐기
-- 토큰은 AES-256-GCM 으로 암호화해 ciphertext + iv 를 분리 컬럼에 저장(평문 저장 없음).
-- EA 사용자당 1건(PK=ea_user_id, 재인가 시 upsert). github_user_id 인덱스로 폐기 조회.
-- ★ forward-only (down 없음).
-- =====================================================================
CREATE TABLE github_user_authorization (
    ea_user_id       BIGINT NOT NULL PRIMARY KEY,
    github_user_id   BIGINT NOT NULL,
    github_login     VARCHAR(255) NOT NULL,
    token_ciphertext BYTEA NOT NULL,
    token_iv         BYTEA NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    updated_at       TIMESTAMP(6) NOT NULL
);

CREATE INDEX ix_github_user_authorization_github_user ON github_user_authorization (github_user_id);
