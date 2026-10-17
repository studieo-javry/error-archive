-- 게스트("로그인 없이 둘러보기") 전용 사용자 표식.
-- 야간 리셋이 실제 사용자(is_guest=FALSE)를 절대 지우지 않도록 handle prefix 추정 대신 명시 컬럼으로 식별.
-- 기존 모든 사용자는 FALSE(실제 계정).
ALTER TABLE iam_user ADD COLUMN is_guest BOOLEAN NOT NULL DEFAULT FALSE;

-- 게스트만 가리키는 부분 인덱스 — 야간 정리 조회(is_guest=TRUE)만 빠르게, 일반 행은 인덱스 미적재.
CREATE INDEX ix_iam_user_is_guest ON iam_user (id) WHERE is_guest = TRUE;
