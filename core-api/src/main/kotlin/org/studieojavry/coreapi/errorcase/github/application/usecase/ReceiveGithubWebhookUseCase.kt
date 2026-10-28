package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubWebhookInboxPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.github.config.GithubAppProperties
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

/**
 * 웹훅 수신 — **서명 검증 + 받은 편지함 적재까지만** 하고 즉시 반환(처리는 [ProcessGithubWebhookEventUseCase] 가 비동기로).
 * GitHub 는 10초 안에 응답이 없으면 실패로 보고, 재전송 시 같은 delivery id 를 쓴다 → PK 충돌로 멱등.
 */
@Component
@ConditionalOnGithubEnabled
class ReceiveGithubWebhookUseCase(
    private val properties: GithubAppProperties,
    private val inbox: GithubWebhookInboxPort,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    enum class Outcome { ACCEPTED, DUPLICATE }

    fun invoke(event: String, deliveryId: String, signature: String?, body: ByteArray): Outcome {
        if (!GithubWebhookSignatureVerifier.isValid(properties.webhookSecret!!, body, signature)) {
            throw GithubWebhookSignatureException("웹훅 서명 불일치")
        }
        val payload = String(body, StandardCharsets.UTF_8)
        val root = objectMapper.readTree(payload)
        val delivery = GithubWebhookInboxPort.InboundDelivery(
            deliveryId = deliveryId,
            event = event,
            action = root.path("action").takeIf { it.isString }?.asString(),
            installationId = root.path("installation").path("id").takeIf { it.isNumber }?.asLong(),
            payload = payload,
        )
        val inserted = inbox.insertIfAbsent(delivery)
        if (!inserted) log.debug { "github webhook duplicate delivery ignored: $deliveryId" }
        return if (inserted) Outcome.ACCEPTED else Outcome.DUPLICATE
    }
}
