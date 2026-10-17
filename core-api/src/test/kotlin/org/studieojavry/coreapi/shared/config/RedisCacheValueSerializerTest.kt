package org.studieojavry.coreapi.shared.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * Redis 캐시 값 직렬화기 회귀 테스트.
 *
 * 과거: 매퍼에 KotlinModule 만 등록 → 대시보드 캐시(watchlist-feed/recent-active-cases 등)의
 * non-empty 결과에 섞인 LocalDateTime 직렬화가 실패 → @Cacheable PUT 예외 → 게이트웨이 503.
 * (빈 결과는 날짜가 없어 우회됐음.) JavaTimeModule 추가로 java.time 이 왕복돼야 한다.
 */
class RedisCacheValueSerializerTest {

    private val serializer = RedisCacheConfig().redisCacheValueSerializer()

    data class ActivityCard(
        val caseId: Long,
        val source: Source,
        val lastActivityAt: LocalDateTime,
        val unread: Long,
    )

    data class FeedResult(val items: List<ActivityCard>)

    enum class Source { COMMENT_POSTED, CASE_RESOLVED }

    @Test
    fun `LocalDateTime 을 품은 non-empty 결과가 왕복 직렬화된다`() {
        val original = FeedResult(
            items = listOf(
                ActivityCard(
                    caseId = 385,
                    source = Source.COMMENT_POSTED,
                    lastActivityAt = LocalDateTime.of(2026, 10, 18, 9, 30, 15),
                    unread = 2,
                ),
            ),
        )

        // before fix: 여기서 InvalidDefinitionException (java.time not supported) 로 터졌음
        val bytes = serializer.serialize(original)
        assertNotNull(bytes)

        val restored = serializer.deserialize(bytes)
        assertEquals(original, restored)
    }

    @Test
    fun `날짜는 timestamp 배열이 아니라 ISO-8601 문자열로 기록된다`() {
        val bytes = serializer.serialize(
            ActivityCard(1, Source.CASE_RESOLVED, LocalDateTime.of(2026, 1, 2, 3, 4, 5), 0),
        )
        val json = String(bytes)
        // ISO 문자열("2026-01-02T03:04:05") 포함, timestamp 배열([2026,1,2,...]) 아님
        assert(json.contains("2026-01-02T03:04:05")) { "ISO 문자열이 아님: $json" }
    }
}
