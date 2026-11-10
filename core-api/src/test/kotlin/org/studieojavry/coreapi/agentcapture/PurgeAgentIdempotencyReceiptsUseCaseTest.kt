package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceipt
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceiptPort
import org.studieojavry.coreapi.agentcapture.application.usecase.PurgeAgentIdempotencyReceiptsUseCase
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** 멱등 영수증 30일 보존 정리 — 경계(now-30d)와 위임 결과 검증. */
class PurgeAgentIdempotencyReceiptsUseCaseTest {

    @Test
    fun `now 에서 30일 이전을 임계로 삭제를 위임하고 건수를 반환한다`() {
        val now = Instant.parse("2026-11-10T00:00:00Z")
        var captured: Instant? = null
        val port = object : IdempotencyReceiptPort {
            override fun find(scopeKey: String, operation: String, key: String): IdempotencyReceipt? = null
            override fun save(receipt: IdempotencyReceipt): IdempotencyReceipt = receipt
            override fun deleteOlderThan(threshold: Instant): Int {
                captured = threshold
                return 7
            }
        }
        val useCase = PurgeAgentIdempotencyReceiptsUseCase(port, Clock.fixed(now, ZoneOffset.UTC))

        val deleted = useCase.invoke()

        assertEquals(7, deleted)
        assertEquals(now.minus(Duration.ofDays(30)), captured)
    }
}
