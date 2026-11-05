package org.studieojavry.coreapi.errorcase.sentry.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.sentry.application.port.SentryWebhookInboxPort
import org.studieojavry.coreapi.errorcase.sentry.config.ConditionalOnSentryEnabled
import org.studieojavry.coreapi.errorcase.sentry.config.SentryProperties
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Sentry 웹훅 수신 — **서명 검증 + 받은 편지함 적재까지만** 하고 즉시 반환(처리는 비동기).
 * Sentry 엔 delivery id 가 없어 멱등 키는 본문 sha256 — 같은 본문 재전송이면 PK 충돌로 무시한다.
 */
@Component
@ConditionalOnSentryEnabled
class ReceiveSentryWebhookUseCase(
    private val properties: SentryProperties,
    private val inbox: SentryWebhookInboxPort,
    private val objectMapper: ObjectMapper,
) {
    private val log = KotlinLogging.logger {}

    enum class Outcome { ACCEPTED, DUPLICATE }

    fun invoke(resource: String, signature: String?, body: ByteArray): Outcome {
        if (!SentryWebhookSignatureVerifier.isValid(properties.clientSecret!!, body, signature)) {
            throw SentryWebhookSignatureException("Sentry 웹훅 서명 불일치")
        }
        val payload = String(body, StandardCharsets.UTF_8)
        val root = objectMapper.readTree(payload)
        val delivery = SentryWebhookInboxPort.InboundDelivery(
            eventKey = sha256Hex(body),
            resource = resource,
            action = root.path("action").takeIf { it.isString }?.asString(),
            payload = payload,
        )
        val inserted = inbox.insertIfAbsent(delivery)
        if (!inserted) log.debug { "sentry webhook duplicate delivery ignored: ${delivery.eventKey}" }
        return if (inserted) Outcome.ACCEPTED else Outcome.DUPLICATE
    }

    private fun sha256Hex(body: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
}
