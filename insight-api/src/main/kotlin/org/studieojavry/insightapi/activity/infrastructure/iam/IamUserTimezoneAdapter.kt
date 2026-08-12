package org.studieojavry.insightapi.activity.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.insightapi.activity.application.port.UserTimezonePort
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * iam-api 의 `/internal/users/{id}/preferences` 호출 + 메모리 캐시(60초).
 *
 * 실패/타임아웃/timezone 파싱 실패 시 UTC 반환 — 잔디 일자 계산이 멈추진 않게.
 */
@Component
class IamUserTimezoneAdapter(
    @Qualifier("iamApiRestClient") private val iamApi: RestClient,
) : UserTimezonePort {

    private val log = KotlinLogging.logger {}
    private val cache = ConcurrentHashMap<Long, Pair<ZoneId, Long>>()
    private val ttlMillis = 60_000L

    override fun fetch(userId: Long): ZoneId {
        val now = System.currentTimeMillis()
        cache[userId]?.let { (zone, fetched) ->
            if (now - fetched < ttlMillis) return zone
        }
        val zone = try {
            val resp = iamApi.get()
                .uri("/internal/users/{id}/preferences", userId)
                .retrieve()
                .body(PreferencesResponse::class.java)
            resp?.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
        } catch (e: RestClientResponseException) {
            log.debug { "iam-api preferences lookup ${e.statusCode} (userId=$userId) — UTC fallback" }
            ZoneId.of("UTC")
        } catch (e: Exception) {
            log.warn(e) { "iam-api preferences lookup failed (userId=$userId) — UTC fallback" }
            ZoneId.of("UTC")
        }
        cache[userId] = zone to now
        return zone
    }

    private data class PreferencesResponse(val timezone: String?)
}
