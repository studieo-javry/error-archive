package org.studieojavry.notiapi.notification.infrastructure

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.notiapi.notification.application.IamUserContactPort

@Component
class IamUserContactAdapter(
    @Qualifier("iamApiRestClient") private val iamApi: RestClient,
) : IamUserContactPort {

    private val log = KotlinLogging.logger {}

    override fun fetch(userId: Long): IamUserContactPort.UserContact? = try {
        val response = iamApi.get()
            .uri("/internal/users/{id}/contact", userId)
            .retrieve()
            .body(IamContactResponse::class.java)
        response?.let {
            IamUserContactPort.UserContact(
                userId = it.userId,
                email = it.email,
                displayName = it.displayName,
                active = it.active,
            )
        }
    } catch (e: RestClientResponseException) {
        if (e.statusCode.value() == 404) null else {
            log.warn(e) { "iam-api contact lookup failed (userId=$userId, status=${e.statusCode}) — channel skipped" }
            null
        }
    } catch (e: Exception) {
        log.warn(e) { "iam-api contact lookup error (userId=$userId) — channel skipped" }
        null
    }

    private data class IamContactResponse(
        val userId: Long,
        val email: String?,
        val displayName: String,
        val status: String,
        val active: Boolean,
    )
}
