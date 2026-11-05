package org.studieojavry.coreapi.errorcase.sentry.infrastructure.inbox

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.sentry.application.port.SentryWebhookInboxPort

@Component
class SentryWebhookInboxAdapter(
    private val jpa: SentryWebhookInboxJpaRepository,
) : SentryWebhookInboxPort {

    override fun insertIfAbsent(delivery: SentryWebhookInboxPort.InboundDelivery): Boolean {
        if (jpa.existsById(delivery.eventKey)) return false
        return try {
            jpa.saveAndFlush(
                SentryWebhookInboxEntity(
                    eventKey = delivery.eventKey,
                    resource = delivery.resource,
                    action = delivery.action,
                    payload = delivery.payload,
                ),
            )
            true
        } catch (_: DataIntegrityViolationException) {
            false   // exists 확인과 INSERT 사이에 같은 본문이 동시에 들어온 경우
        }
    }
}
