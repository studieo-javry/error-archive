package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceiptPort
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 에이전트 멱등 영수증 보존 정리(이슈 #144 §5: 성공 응답 캐시 기본 보존 30일).
 *
 * 영수증만 지운다 — 엔티티 원본 ID 매핑과 CaptureImport UNIQUE 제약은 해당 기록이 존재하는 동안 유지되므로
 * 영수증이 사라져도 반영 멱등/충돌 판정(capture_import.request_hash)은 그대로 보장된다.
 */
@Service
class PurgeAgentIdempotencyReceiptsUseCase(
    private val receipts: IdempotencyReceiptPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(): Int = receipts.deleteOlderThan(Instant.now(clock).minus(RETENTION))

    companion object {
        private val RETENTION: Duration = Duration.ofDays(30)
    }
}
