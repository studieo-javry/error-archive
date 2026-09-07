package org.studieojavry.publishapi.publishment.infrastructure.core

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.studieojavry.publishapi.publishment.application.port.OriginalCaseStatusReaderPort
import java.time.LocalDateTime

@Component
class OriginalCaseStatusAdapter(
    @Qualifier("coreApiRestClient") private val coreApi: RestClient,
) : OriginalCaseStatusReaderPort {

    private val log = KotlinLogging.logger {}

    override fun fetchStatuses(caseIds: Set<Long>): Map<Long, OriginalCaseStatusReaderPort.Status>? {
        if (caseIds.isEmpty()) return emptyMap()
        return try {
            val resp = coreApi.post()
                .uri("/internal/error-cases/status")
                .body(StatusRequest(caseIds.toList()))
                .retrieve()
                .body(StatusResponse::class.java)
            resp?.statuses.orEmpty()
                .associate { it.id to OriginalCaseStatusReaderPort.Status(updatedAt = it.updatedAt) }
        } catch (e: Exception) {
            // 조회 실패는 치명적이지 않음 → null 반환. 호출자가 판정을 건너뜀(상태 변경 없이 그대로 노출).
            // (emptyMap 을 돌려주면 "전부 삭제됨" 으로 오판하므로 반드시 null.)
            log.warn(e) { "core-api case status lookup failed (caseIds=${caseIds.size})" }
            null
        }
    }

    private data class StatusRequest(val caseIds: List<Long>)
    private data class StatusResponse(val statuses: List<StatusItem>)
    private data class StatusItem(val id: Long, val updatedAt: LocalDateTime)
}
