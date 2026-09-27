-- =============================================================================
-- V2: error_case (owner_user_id, updated_at) 복합 인덱스 추가
--
-- 배경: GetMyRecentActiveCasesUseCase 의 후보 선정 쿼리
--   SELECT ... FROM error_case WHERE owner_user_id = ? ORDER BY updated_at DESC LIMIT 50
-- 가 owner_user_id 인덱스 부재로 Seq Scan + Sort 로 수행됨(전체 케이스 수에 O(N)).
-- 소규모(2k) 측정에선 <0.5ms 로 무해했으나 케이스 수 증가 시 선형 악화 →
-- 소유자별 최근수정 상위 N건 조회를 인덱스 스캔으로 전환한다.
--
-- updated_at DESC 로 정렬 방향까지 인덱스에 담아 Sort 단계를 제거한다.
-- =============================================================================

CREATE INDEX ix_error_case_owner_updated ON error_case (owner_user_id, updated_at DESC);
