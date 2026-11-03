package org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxEntity.InboxStatus
import org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxJpaRepository
import java.time.Instant

/**
 * 웹훅 받은 편지함 운영 액션 — 운영자가 `/internal/admin/github/webhooks/...` 로 호출(일반 사용자 API 아님).
 * 되돌린 건은 처리기([org.studieojavry.coreapi.errorcase.github.infrastructure.inbox.GithubWebhookInboxProcessor])가 다음 주기에 가져간다.
 */
@Service
@ConditionalOnGithubEnabled
class GithubWebhookAdminUseCases(
    private val inboxRepo: GithubWebhookInboxJpaRepository,
) {
    private val log = KotlinLogging.logger {}

    fun list(status: InboxStatus?, limit: Int): List<GithubWebhookInboxEntity> =
        inboxRepo.findRecent(status, Limit.of(limit.coerceIn(1, 500)))

    /**
     * 한 건을 다시 처리 대기열로(시도 횟수 0). FAILED·PENDING 은 그대로 되돌리고, 이미 끝난 DONE·SKIPPED 는 [force] 일 때만 —
     * 코드 수정 뒤 예전 웹훅을 다시 적용하고 싶을 때.
     */
    @Transactional
    fun retry(deliveryId: String, force: Boolean): GithubWebhookInboxEntity {
        val row = inboxRepo.findById(deliveryId).orElseThrow { NoSuchElementException("github webhook delivery not found: $deliveryId") }
        val was = row.status
        if (!force && (was == InboxStatus.DONE || was == InboxStatus.SKIPPED)) throw AlreadyProcessedException(deliveryId, was)
        row.requeue()
        log.info { "github webhook admin: retry delivery=$deliveryId event=${row.event} (was $was, force=$force)" }
        return inboxRepo.save(row)
    }

    /** FAILED 전부를 다시 처리 대기열로 — 되돌린 건수. */
    @Transactional
    fun retryAllFailed(): Int = inboxRepo.requeueAllFailed(Instant.now())
        .also { log.info { "github webhook admin: retry-failed requeued $it rows" } }

    class AlreadyProcessedException(deliveryId: String, status: InboxStatus) :
        RuntimeException("이미 처리된 웹훅($status): $deliveryId — 다시 적용하려면 force=true")
}
