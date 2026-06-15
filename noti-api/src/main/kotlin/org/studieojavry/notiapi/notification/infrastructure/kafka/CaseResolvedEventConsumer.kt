package org.studieojavry.notiapi.notification.infrastructure.kafka

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import org.studieojavry.notiapi.notification.application.ReceiveCaseResolvedEventUseCase
import tools.jackson.databind.ObjectMapper

/**
 * `notification-events.case-resolved.v1` 토픽 consumer.
 * core-api 가 케이스 status RESOLVED 전환 시 fan-out 메시지 1건 발화.
 * recipients = 워크스페이스 멤버 ∪ watchlist 사용자 (actor 제외).
 */
@Component
class CaseResolvedEventConsumer(
    private val receiveCaseResolvedEventUseCase: ReceiveCaseResolvedEventUseCase,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    @KafkaListener(topics = ["notification-events.case-resolved.v1"], groupId = "noti-api")
    fun onCaseResolved(value: String) {
        try {
            val msg = objectMapper.readValue(value, CaseResolvedMessage::class.java)
            receiveCaseResolvedEventUseCase.invoke(
                ReceiveCaseResolvedEventUseCase.CaseResolvedEvent(
                    recipientUserIds = msg.recipientUserIds,
                    actorUserId = msg.actorUserId,
                    errorCaseId = msg.errorCaseId,
                    caseTitle = msg.caseTitle,
                    workspaceId = msg.workspaceId,
                )
            )
        } catch (ex: Exception) {
            log.error(ex) { "failed to process case-resolved event: $value" }
            throw ex
        }
    }

    data class CaseResolvedMessage(
        val recipientUserIds: List<Long>,
        val actorUserId: Long,
        val errorCaseId: Long,
        val caseTitle: String,
        val workspaceId: Long?,
    )
}
