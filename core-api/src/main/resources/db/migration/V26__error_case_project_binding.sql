-- P1-0(B): Case 를 ProjectBinding 에 명시적으로 귀속(이슈 #144 "Case.project↔ProjectBinding 대응은 사용자가 명시 설정").
-- case:read 스코프가 이 값으로 필터한다(이름/ repo 동일만으로 권한 확대 금지 → 명시 매핑 기준).
-- nullable — 기존/수동 작성 케이스는 미귀속(null) 하위호환. FK 없이 값으로 스코프(크로스 애그리게잇 결합 회피).
ALTER TABLE error_case ADD COLUMN project_binding_id BIGINT;
CREATE INDEX ix_error_case_project_binding ON error_case (project_binding_id);
