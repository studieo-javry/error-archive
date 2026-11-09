package org.studieojavry.insightapi.activity.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.insightapi.activity.application.port.ActivityDailyRepositoryPort
import org.studieojavry.insightapi.activity.application.port.ActivityEventRepositoryPort
import org.studieojavry.insightapi.activity.application.port.UserTimezonePort
import org.studieojavry.insightapi.activity.config.ActivityWeightProperties
import org.studieojavry.insightapi.activity.domain.ActivityEvent
import org.studieojavry.insightapi.activity.domain.ActivityOrigin
import org.studieojavry.insightapi.activity.domain.ActivityType
import java.time.Instant

/**
 * Kafka consumer 가 호출. raw INSERT (멱등) → daily UPSERT.
 *
 * 동일 트랜잭션 — raw 가 새로 들어간 경우에만 daily 갱신. 중복(idempotencyKey 충돌) 은 *조용히 끝* —
 * 이미 같은 메시지가 처리되었음.
 *
 * score 결정 정책:
 *  - 메시지에 score 가 와 있으면 그 값 사용 (publish 시점 정책 보존)
 *  - 없거나 0 이면 서버 측 yml 가중치 적용 (정책 회귀 시 활용)
 */
@Service
class IngestActivityEventUseCase(
    private val eventRepository: ActivityEventRepositoryPort,
    private val dailyRepository: ActivityDailyRepositoryPort,
    private val userTimezone: UserTimezonePort,
    private val weights: ActivityWeightProperties,
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun invoke(input: Input) {
        val type = ActivityType.fromCodeOrNull(input.typeCode) ?: run {
            log.warn { "unknown activity type: ${input.typeCode} — dropped" }
            return
        }
        val score = if (input.score > 0) input.score else weights.weightOf(type)
        val event = ActivityEvent.create(
            userId = input.userId,
            type = type,
            occurredAt = input.occurredAt,
            score = score,
            idempotencyKey = input.idempotencyKey,
            metaJson = input.metaJson,
            origin = input.origin,
        )
        val newId = eventRepository.insertIfAbsent(event)
        if (newId == null) {
            log.debug { "duplicate activity event skipped: key=${input.idempotencyKey}" }
            return
        }
        // origin 게이트: AGENT_IMPORT(에이전트 초안 반영)는 raw 원장엔 적재하되 잔디/KPI(daily)엔 집계하지
        // 않는다. raw 에 origin 을 남겨, 정책 변경 시 daily 를 rebuild 해도 동일하게 제외된다.
        if (input.origin == ActivityOrigin.AGENT_IMPORT) {
            log.debug { "agent-import activity excluded from daily: key=${input.idempotencyKey}" }
            return
        }
        val tz = userTimezone.fetch(input.userId)
        val date = input.occurredAt.atZone(tz).toLocalDate()
        dailyRepository.increment(input.userId, date, type, score)
    }

    data class Input(
        val userId: Long,
        val typeCode: String,
        val occurredAt: Instant,
        val score: Int,
        val idempotencyKey: String,
        val metaJson: String?,
        val origin: ActivityOrigin = ActivityOrigin.USER,
    )
}
