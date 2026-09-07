package org.studieojavry.publishapi.publishment.infrastructure.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.publishapi.publishment.application.port.CoreCaseAccessDeniedException
import org.studieojavry.publishapi.publishment.application.port.CoreCaseDataPort
import org.studieojavry.publishapi.publishment.application.port.CoreCaseFullData
import org.studieojavry.publishapi.publishment.application.port.CoreCaseNotFoundException

@Component
class CoreCaseDataAdapter(
    @Qualifier("coreApiRestClient") private val coreApi: RestClient,
) : CoreCaseDataPort {

    private val log = KotlinLogging.logger {}

    override fun fetchFullData(caseId: Long): CoreCaseFullData? = try {
        coreApi.get()
            .uri("/internal/error-cases/{id}/full-data", caseId)
            .retrieve()
            .body(CoreCaseFullData::class.java)
    } catch (e: RestClientResponseException) {
        when (e.statusCode.value()) {
            404 -> throw CoreCaseNotFoundException("case not found: $caseId")
            403 -> throw CoreCaseAccessDeniedException("not the owner of case $caseId")
            else -> {
                log.warn(e) { "core-api full-data lookup failed (caseId=$caseId, status=${e.statusCode})" }
                null
            }
        }
    } catch (e: Exception) {
        log.warn(e) { "core-api full-data error (caseId=$caseId)" }
        null
    }
}
