package org.studieojavry.coreapi.errorcase.github.infrastructure.inbox

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubWebhookInboxPort

@Component
class GithubWebhookInboxAdapter(
    private val jpa: GithubWebhookInboxJpaRepository,
) : GithubWebhookInboxPort {

    override fun insertIfAbsent(delivery: GithubWebhookInboxPort.InboundDelivery): Boolean {
        if (jpa.existsById(delivery.deliveryId)) return false
        return try {
            jpa.saveAndFlush(
                GithubWebhookInboxEntity(
                    deliveryId = delivery.deliveryId,
                    event = delivery.event,
                    action = delivery.action,
                    installationId = delivery.installationId,
                    payload = delivery.payload,
                ),
            )
            true
        } catch (_: DataIntegrityViolationException) {
            false   // exists 확인과 INSERT 사이에 같은 delivery 가 동시에 들어온 경우
        }
    }
}
