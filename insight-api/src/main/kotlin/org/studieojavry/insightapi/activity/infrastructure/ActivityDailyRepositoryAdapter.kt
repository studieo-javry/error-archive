package org.studieojavry.insightapi.activity.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.studieojavry.insightapi.activity.application.port.ActivityDailyRepositoryPort
import org.studieojavry.insightapi.activity.domain.ActivityDaily
import org.studieojavry.insightapi.activity.domain.ActivityType
import tools.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

/**
 * `insight_activity_daily` 증분 갱신 — PostgreSQL native upsert.
 *
 * breakdown_json 은 `{TYPE: count}` 형태. 같은 type 가중 시 count + 1.
 * jsonb concat(`||`) 연산자로 *atomic* 갱신.
 */
@Repository
class ActivityDailyRepositoryAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val jpa: ActivityDailyJpaRepository,
    private val objectMapper: ObjectMapper,
) : ActivityDailyRepositoryPort {

    override fun increment(userId: Long, date: LocalDate, type: ActivityType, score: Int) {
        val typeStr = type.name
        val initialBreakdown = objectMapper.writeValueAsString(mapOf(typeStr to 1))
        val now = Timestamp.from(Instant.now())
        /**
         * 사용자의 하루 활동 통계를 누적 저장하는 upsert Query
         * 1. 활동 타입 이름 추출 (type.name -> "CASE_ADDED")
         * 2. 최초 저장용 JSON 생성 {"CASE_ADDED": 1}
         * 3. 현재 시간 생성
         * 4. 오늘 해당 사용자의 통계 row INSERT 시도
         * 5. 이미 있으면 UPDATE
         *    - event_count + 1
         *    - score_sum + score
         *    - breakdown_json 안의 해당 type count + 1
         *    - updated_at 갱신
         */
        jdbcTemplate.update(
            """
            INSERT INTO insight_activity_daily
              (user_id, activity_date, event_count, score_sum, breakdown_json, updated_at)
            VALUES (?, ?, 1, ?, ?::jsonb, ?)
            ON CONFLICT (user_id, activity_date) DO UPDATE
            SET event_count   = insight_activity_daily.event_count + 1,
                score_sum     = insight_activity_daily.score_sum + EXCLUDED.score_sum,
                breakdown_json = jsonb_set(
                  insight_activity_daily.breakdown_json,
                  ARRAY[?],
                  to_jsonb(
                    COALESCE((insight_activity_daily.breakdown_json ->> ?)::int, 0) + 1
                  )
                ),
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            userId, date, score, initialBreakdown, now,
            typeStr, typeStr,
        )
    }

    override fun findInRange(userId: Long, from: LocalDate, to: LocalDate): List<ActivityDaily> =
        jpa.findInRange(userId, from, to).map { entity ->
            val breakdown = try {
                objectMapper.readValue(entity.breakdownJson, Map::class.java)
                    .mapNotNull { (k, v) -> (k as? String)?.let { it to (v as Number).toInt() } }
                    .toMap()
            } catch (e: Exception) {
                emptyMap()
            }
            ActivityDaily.rehydrate(
                userId = entity.userId,
                activityDate = entity.activityDate,
                eventCount = entity.eventCount,
                scoreSum = entity.scoreSum,
                breakdown = breakdown,
                updatedAt = entity.updatedAt,
            )
        }
}
