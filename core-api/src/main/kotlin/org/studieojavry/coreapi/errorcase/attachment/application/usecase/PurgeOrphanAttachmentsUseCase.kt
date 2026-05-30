package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.config.AttachmentStorageProperties
import java.time.Instant

/**
 * 미연결(errorCaseId IS NULL) + TTL 경과 첨부를 정리하는 GC.
 *
 * 업로드는 됐지만 끝내 에러케이스로 연결되지 않은 첨부(폼 이탈/크래시/네트워크 끊김)는
 * 파일+DB row 가 누수된다. 스케줄러가 이 use case 를 주기적으로 호출한다.
 *
 * 배치 루프:
 *  - `findUnlinkedOlderThan` 으로 오래된 순 limit 건씩 조회.
 *  - 각 건을 `AttachmentDeleter`(독립 트랜잭션·멱등)로 삭제 → 한 건 실패가 배치 전체를 막지 않음.
 *  - 진행이 없으면(전부 실패) 멈춰 무한 루프 방지 → 다음 주기에 재시도.
 *
 * 트랜잭션 경계: 일부러 클래스 레벨 @Transactional 을 두지 않는다. 각 삭제가 독립 커밋되어야
 * 큰 트랜잭션/락을 피하고, 중간 실패에도 이미 지운 건 보존된다.
 */
@Service
class PurgeOrphanAttachmentsUseCase(
    private val repository: ErrorCaseAttachmentRepositoryPort,
    private val deleter: AttachmentDeleter,
    private val properties: AttachmentStorageProperties,
) {
    private val log = KotlinLogging.logger {}

    /** @return 실제로 삭제한 첨부 수 */
    fun invoke(): Int {
        val threshold = Instant.now().minus(properties.orphanTtl)
        val batchSize = properties.orphanGcBatchSize
        var totalDeleted = 0

        repeat(MAX_BATCHES) {
            val batch = repository.findUnlinkedOlderThan(threshold, batchSize)
            if (batch.isEmpty()) return totalDeleted.also { logIfAny(it) }

            var deletedThisBatch = 0
            for (attachment in batch) {
                runCatching { deleter.delete(attachment) }
                    .onSuccess { deletedThisBatch++ }
                    .onFailure { log.warn(it) { "[attachment-gc] purge failed markerId=${attachment.markerId}" } }
            }
            totalDeleted += deletedThisBatch

            // 진행이 없으면(전부 실패) 같은 배치를 계속 다시 집게 되므로 중단.
            if (deletedThisBatch == 0) return totalDeleted.also { logIfAny(it) }
            // 마지막(부분) 배치면 종료.
            if (batch.size < batchSize) return totalDeleted.also { logIfAny(it) }
        }
        logIfAny(totalDeleted)
        return totalDeleted
    }

    private fun logIfAny(count: Int) {
        if (count > 0) log.info { "[attachment-gc] purged $count orphan attachments (ttl=${properties.orphanTtl})" }
    }

    companion object {
        /** 한 실행에서의 배치 상한 (안전장치). batchSize 와 곱한 만큼이 1회 최대 처리량. */
        private const val MAX_BATCHES = 1000
    }
}
