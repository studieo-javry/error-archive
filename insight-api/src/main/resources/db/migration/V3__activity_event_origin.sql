-- Agent Capture (P0) PR4 — 활동 출처(origin) 게이트.
--
-- 에이전트 초안 반영(AGENT_IMPORT)으로 생성된 활동은 잔디/KPI(insight_activity_daily)에 집계하지 않는다.
-- 게이트 판단은 ingest 시점(IngestActivityEventUseCase)에 하지만, daily 는 raw(insight_activity_event)를
-- 다시 읽어 truncate-and-rebuild 하므로 origin 을 raw 에 **보존**해야 재구성에서도 동일하게 제외된다.
--
-- nullable: 과거 행·일반 활동(발행 측 origin 미전송)은 NULL → 소비 측에서 USER 로 간주(집계 포함).
-- 엔티티 ActivityEventEntity.origin 과 정합(validate).

ALTER TABLE insight_activity_event
    ADD COLUMN origin VARCHAR(16);
