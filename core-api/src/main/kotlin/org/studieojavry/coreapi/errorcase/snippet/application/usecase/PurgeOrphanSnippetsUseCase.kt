package org.studieojavry.coreapi.errorcase.snippet.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant
import org.springframework.stereotype.Service
import org.studieojavry.coreapi.errorcase.attachment.application.usecase.PurgeOrphanAttachmentsUseCase
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.config.SnippetProperties


/**
 * 미연결(errorCaseId IS NULL) + TTL 경과 스니펫 GC. 첨부 PurgeOrphanAttachmentsUseCase 와 대칭.
 * 스니펫은 파일이 없어 row 만 지우면 되므로 SnippetDeleter 가 단순하다.
 */
@Service
class PurgeOrphanSnippetsUseCase(
    private val repository: CodeSnippetRepositoryPort,
    private val deleter: SnippetDeleter,
    private val properties: SnippetProperties,
) {
    private val log = KotlinLogging.logger {}

    /** @return 삭제한 스니펫 수 */
    fun invoke(): Int {
        val threshold = Instant.now().minus(properties.orphanTtl)
        val batchSize = properties.orphanGcBatchSize
        var totalDeleted = 0

        repeat(MAX_BATCHES) {
            val batch = repository.findUnlinkedOlderThan(threshold, batchSize)
            if (batch.isEmpty()) return totalDeleted.also { logIfAny(it) }

            var deletedThisBatch = 0
            for (snippet in batch) {
                runCatching { deleter.delete(snippet) }
                    .onSuccess { deletedThisBatch++ }
                    .onFailure { log.warn(it) { "[snippet-gc] purge failed markerId=${snippet.markerId}" } }
            }
            totalDeleted += deletedThisBatch

            if (deletedThisBatch == 0) return totalDeleted.also { logIfAny(it) }
            if (batch.size < batchSize) return totalDeleted.also { logIfAny(it) }
        }
        logIfAny(totalDeleted)
        return totalDeleted
    }

    private fun logIfAny(count: Int) {
        if (count > 0) log.info { "[snippet-gc] purged $count orphan snippets (ttl=${properties.orphanTtl})" }
    }

    companion object {
        private const val MAX_BATCHES = 1000
    }
}
