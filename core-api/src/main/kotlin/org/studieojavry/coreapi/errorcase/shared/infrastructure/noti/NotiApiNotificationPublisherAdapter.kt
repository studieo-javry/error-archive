package org.studieojavry.coreapi.errorcase.shared.infrastructure.noti

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.coreapi.errorcase.shared.application.port.NotificationPublisherPort

/**
 * noti-api 의 `POST /internal/notifications/mentions` 동기 호출.
 * CircuitBreaker `notiApi` — noti-api 가 다운/지연되면 *조용히 fallback*.
 */
@Component
@ConditionalOnProperty(prefix = "noti.publisher", name = ["mode"], havingValue = "http")
class NotiApiNotificationPublisherAdapter(
    @Qualifier("notiApiRestClient") private val notiApi: RestClient,
    cbFactory: CircuitBreakerFactory<*, *>,
) : NotificationPublisherPort {

    private val log = KotlinLogging.logger {}
    private val cb = cbFactory.create("notiApi")

    override fun publishMentions(event: NotificationPublisherPort.MentionEvent) {
        if (event.recipientUserIds.isEmpty()) return
        cb.run(
            {
                try {
                    notiApi.post()
                        .uri("/internal/notifications/mentions")
                        .body(MentionRequest(
                            recipientUserIds = event.recipientUserIds,
                            actorUserId = event.actorUserId,
                            errorCaseId = event.errorCaseId,
                            commentId = event.commentId,
                            snippet = event.snippet,
                        ))
                        .retrieve()
                        .toBodilessEntity()
                    Unit
                } catch (ex: RestClientResponseException) {
                    log.warn(ex) { "noti-api mention publish failed: status=${ex.statusCode}" }
                }
            },
            { t -> log.warn(t) { "noti-api unavailable on publishMentions → silently dropped" }; Unit }
        )
    }

    private data class MentionRequest(
        val recipientUserIds: List<Long>,
        val actorUserId: Long,
        val errorCaseId: Long,
        val commentId: Long,
        val snippet: String,
    )
}