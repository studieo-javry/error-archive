-- PR-Y: 세션→capture 프로젝트 문맥 배선. capture_session / agent_capture 에 project_binding_id 추가.
-- #162 에서 토대(project_binding)만 만들고 이 두 ALTER 는 실제 배선과 함께 여기로 미뤘다(죽은 컬럼 방지).
-- nullable, FK 없음 — error_case.project_binding_id 와 동일하게 "값 기반 스코프"(크로스 애그리거트 결합·삭제순서 회피).
ALTER TABLE capture_session ADD COLUMN project_binding_id BIGINT;
ALTER TABLE agent_capture   ADD COLUMN project_binding_id BIGINT;
