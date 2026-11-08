-- =====================================================================
-- V19: github_user_authorization — refresh 토큰 + 만료 시각 컬럼 추가
--   · GitHub App 이 "Expire user authorization tokens" 를 켜면 사용자 토큰이 ~8h 뒤 만료된다.
--     그 경우 OAuth 응답에 refresh_token(~6개월) 과 expires_in 이 함께 와, 만료 전 갱신하면 재인가 없이 토큰을 유지한다.
--   · 세 컬럼 모두 NULL 허용:
--       - App 이 토큰 만료를 끈 경우(= 토큰 무기한)엔 refresh_token/expires_in 자체가 안 온다 → NULL.
--       - V18 로 이미 저장된 기존 행(갱신 정보 없음) → NULL (다음 재인가 때 채워짐).
--     refresh_token 이 NULL 이면 선제 갱신을 건너뛰고 기존 동작(4xx → 폐기 → 재인가)으로 떨어진다.
-- refresh_token 도 사용자 자원 접근권을 쥔 비밀 → access token 과 동일하게 AES-256-GCM(ciphertext + iv 분리) 암호화.
-- ★ forward-only (down 없음). 기존 행에 추가만 하므로 데이터 보존.
-- =====================================================================
ALTER TABLE github_user_authorization
    ADD COLUMN refresh_token_ciphertext BYTEA,
    ADD COLUMN refresh_token_iv         BYTEA,
    ADD COLUMN access_token_expires_at  TIMESTAMP(6),
    ADD COLUMN refresh_token_expires_at TIMESTAMP(6);
