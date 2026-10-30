package org.studieojavry.coreapi.errorcase.github.application.port

interface GithubWebhookInboxPort {
    /** 받은 편지함에 적재. 같은 deliveryId 가 이미 있으면 false(중복 전달 — 무시). */
    fun insertIfAbsent(delivery: InboundDelivery): Boolean

    data class InboundDelivery(
        val deliveryId: String,
        val event: String,
        val action: String?,
        val installationId: Long?,
        val payload: String,
    )
}
