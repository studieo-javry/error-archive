package org.studieojavry.insightapi.activity.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.studieojavry.insightapi.activity.application.port.ActivityEventRepositoryPort
import org.studieojavry.insightapi.activity.domain.ActivityEvent
import java.sql.Timestamp

@Repository
class ActivityEventRepositoryAdapter(
    private val jdbcTemplate: JdbcTemplate,
) : ActivityEventRepositoryPort {

    /**
     * 멱등 INSERT — `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING id`.
     *  - 신규면 생성된 id, 중복(같은 idempotencyKey)이면 반환 row 없음 → null.
     *  - 충돌이 **예외를 던지지 않으므로** 트랜잭션이 rollback-only 로 오염되지 않는다.
     *    (기존 exists-precheck + try/catch(DataIntegrityViolationException) 방식은 동시 중복 수신 시
     *     UNIQUE 위반 → PG 가 트랜잭션을 abort → catch 로 삼켜도 commit 시
     *     UnexpectedRollbackException → DLQ 유발. native upsert 로 레이스 세이프.)
     */
    override fun insertIfAbsent(event: ActivityEvent): Long? =
        jdbcTemplate.query(
            """
            INSERT INTO insight_activity_event
              (user_id, type, occurred_at, score, idempotency_key, meta_json, created_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            RETURNING id
            """.trimIndent(),
            { rs, _ -> rs.getLong("id") },
            event.userId,
            event.type.name,
            Timestamp.from(event.occurredAt),
            event.score,
            event.idempotencyKey,
            event.metaJson,
            Timestamp.from(event.createdAt),
        ).firstOrNull()
}
