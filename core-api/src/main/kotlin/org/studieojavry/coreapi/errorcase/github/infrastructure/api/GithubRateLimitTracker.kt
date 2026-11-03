package org.studieojavry.coreapi.errorcase.github.infrastructure.api

import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubRateLimitPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * 설치별 GitHub 호출 한도 잔량 — installation 토큰 호출의 응답 헤더를 [GithubAppApiAdapter] 가 넘겨 준다.
 *
 * - 자원은 core(REST)·graphql 만 본다. App JWT 호출(`/app/...`)은 한도가 따로라 넘기지 않는다.
 * - 같은 리셋 시각 안에서는 더 적은 잔량을 믿는다(동시 응답이 순서 없이 도착). 리셋 시각이 더 늦으면 새 창.
 * - 메모리에만 둔다 — 인스턴스·재기동마다 처음엔 모르지만, 다음 응답 한 번이면 다시 안다(모를 땐 막지 않음).
 */
@Component
@ConditionalOnGithubEnabled
class GithubRateLimitTracker(
    private val properties: GithubAppProperties,
) : GithubRateLimitPort {

    private data class Key(val installationId: Long, val resource: String)
    data class Window(val limit: Int, val remaining: Int, val resetAt: Instant)

    private val windows = ConcurrentHashMap<Key, Window>()

    fun record(installationId: Long, headers: HttpHeaders) {
        val resource = headers.getFirst("x-ratelimit-resource") ?: RESOURCE_CORE
        if (resource !in TRACKED) return
        val limit = headers.getFirst("x-ratelimit-limit")?.toIntOrNull() ?: return
        val remaining = headers.getFirst("x-ratelimit-remaining")?.toIntOrNull() ?: return
        val resetAt = headers.getFirst("x-ratelimit-reset")?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return
        windows.merge(Key(installationId, resource), Window(limit, remaining, resetAt)) { old, new ->
            when {
                new.resetAt.isAfter(old.resetAt) -> new
                new.resetAt == old.resetAt && new.remaining < old.remaining -> new
                else -> old
            }
        }
    }

    /** [resource] 한도가 바닥났으면(남은 0) 리셋까지 — 사용자 요청도 GitHub 를 부르지 않고 바로 한도 초과로 돌려준다. */
    fun exhaustedWait(installationId: Long, resource: String, now: Instant = Instant.now()): Duration? =
        windows[Key(installationId, resource)]?.takeIf { it.remaining <= 0 }?.waitFrom(now)

    override fun backgroundWait(installationId: Long, now: Instant): Duration? =
        TRACKED.mapNotNull { windows[Key(installationId, it)] }
            .filter { it.remaining <= reserve(it.limit) }
            .mapNotNull { it.waitFrom(now) }
            .maxOrNull()

    /** 지표용 — 아직 리셋 전인 창만 (설치 id, 자원) → 창. */
    fun snapshot(now: Instant = Instant.now()): Map<Pair<Long, String>, Window> =
        windows.filterValues { it.resetAt.isAfter(now) }.mapKeys { (k, _) -> k.installationId to k.resource }

    private fun reserve(limit: Int): Int = ceil(limit * properties.rateLimit.backgroundReserveRatio).toInt()

    /** 리셋이 지났으면 null(새 창이라 잔량을 모름 → 막지 않음). */
    private fun Window.waitFrom(now: Instant): Duration? = Duration.between(now, resetAt).takeIf { !it.isNegative && !it.isZero }

    companion object {
        const val RESOURCE_CORE = "core"
        const val RESOURCE_GRAPHQL = "graphql"
        private val TRACKED = listOf(RESOURCE_CORE, RESOURCE_GRAPHQL)
    }
}
