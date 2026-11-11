package org.studieojavry.coreapi.agentcapture.infrastructure.jpa.adapter

import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSessionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceipt
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceiptPort
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionContent
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.AgentCaptureJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureAttemptJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureSessionJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureSessionLinkJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureSolutionJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.IdempotencyReceiptJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.AgentCaptureEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureAttemptEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSessionEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSessionLinkEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureSolutionCandidateEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.IdempotencyReceiptEntity
import java.util.UUID

@Repository
class AgentCaptureRepositoryAdapter(
    private val jpa: AgentCaptureJpaRepository,
    private val objectMapper: ObjectMapper,
) : AgentCaptureRepositoryPort {

    override fun save(capture: AgentCapture): AgentCapture {
        val entity = AgentCaptureEntity(
            id = capture.id,
            captureUuid = capture.captureUuid,
            ownerUserId = capture.ownerUserId,
            originatingConnectionId = capture.originatingConnectionId,
            status = capture.status,
            revision = capture.revision,
            title = capture.title,
            project = capture.project,
            content = objectMapper.writeValueAsString(capture.content),
            createdAt = capture.createdAt,
            updatedAt = capture.updatedAt,
            completedAt = capture.completedAt,
        )
        // saveAndFlush: @Version(revision) 증가가 flush 시점에 일어나므로, 응답에 새 revision 을
        // 반영하려면 즉시 flush 해야 한다(그래야 멱등 영수증에도 올바른 revision 이 저장된다).
        return jpa.saveAndFlush(entity).toDomain()
    }

    override fun findByUuid(captureUuid: UUID): AgentCapture? =
        jpa.findByCaptureUuid(captureUuid)?.toDomain()

    override fun findByUuidAndOwner(captureUuid: UUID, ownerUserId: Long): AgentCapture? =
        jpa.findByCaptureUuidAndOwnerUserId(captureUuid, ownerUserId)?.toDomain()

    private fun AgentCaptureEntity.toDomain(): AgentCapture = AgentCapture.reconstitute(
        id = id!!,
        captureUuid = captureUuid,
        ownerUserId = ownerUserId,
        originatingConnectionId = originatingConnectionId,
        status = status,
        revision = revision,
        title = title,
        project = project,
        content = objectMapper.readValue(content, CaptureContent::class.java),
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )
}

@Repository
class CaptureAttemptRepositoryAdapter(
    private val jpa: CaptureAttemptJpaRepository,
    private val objectMapper: ObjectMapper,
) : CaptureAttemptRepositoryPort {

    override fun save(attempt: CaptureAttempt): CaptureAttempt {
        val entity = CaptureAttemptEntity(
            id = attempt.id,
            captureId = attempt.captureId,
            clientAttemptId = attempt.clientAttemptId,
            orderIndex = attempt.orderIndex,
            result = attempt.result,
            attemptType = attempt.attemptType,
            detail = objectMapper.writeValueAsString(attempt.detail),
            createdAt = attempt.createdAt,
            updatedAt = attempt.updatedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByCaptureAndClientId(captureId: Long, clientAttemptId: String): CaptureAttempt? =
        jpa.findByCaptureIdAndClientAttemptId(captureId, clientAttemptId)?.toDomain()

    override fun listByCapture(captureId: Long): List<CaptureAttempt> =
        jpa.findByCaptureIdOrderByOrderIndexAscIdAsc(captureId).map { it.toDomain() }

    override fun countByCapture(captureId: Long): Int = jpa.countByCaptureId(captureId).toInt()

    override fun clientAttemptIds(captureId: Long): Set<String> = jpa.clientAttemptIds(captureId).toSet()

    private fun CaptureAttemptEntity.toDomain(): CaptureAttempt = CaptureAttempt.reconstitute(
        id = id!!,
        captureId = captureId,
        clientAttemptId = clientAttemptId,
        orderIndex = orderIndex,
        result = result,
        attemptType = attemptType,
        detail = objectMapper.readValue(detail, AttemptDetail::class.java),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

@Repository
class CaptureSolutionRepositoryAdapter(
    private val jpa: CaptureSolutionJpaRepository,
    private val objectMapper: ObjectMapper,
) : CaptureSolutionRepositoryPort {

    override fun save(solution: CaptureSolutionCandidate): CaptureSolutionCandidate {
        val entity = CaptureSolutionCandidateEntity(
            id = solution.id,
            captureId = solution.captureId,
            clientSolutionId = solution.clientSolutionId,
            title = solution.title,
            content = objectMapper.writeValueAsString(solution.content),
            createdAt = solution.createdAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByCaptureAndClientId(captureId: Long, clientSolutionId: String): CaptureSolutionCandidate? =
        jpa.findByCaptureIdAndClientSolutionId(captureId, clientSolutionId)?.toDomain()

    override fun listByCapture(captureId: Long): List<CaptureSolutionCandidate> =
        jpa.findByCaptureIdOrderByIdAsc(captureId).map { it.toDomain() }

    private fun CaptureSolutionCandidateEntity.toDomain(): CaptureSolutionCandidate =
        CaptureSolutionCandidate.reconstitute(
            id = id!!,
            captureId = captureId,
            clientSolutionId = clientSolutionId,
            title = title,
            content = objectMapper.readValue(content, SolutionContent::class.java),
            createdAt = createdAt,
        )
}

@Repository
class CaptureSessionRepositoryAdapter(
    private val jpa: CaptureSessionJpaRepository,
    private val linkJpa: CaptureSessionLinkJpaRepository,
) : CaptureSessionRepositoryPort {

    override fun save(session: CaptureSession): CaptureSession {
        val entity = CaptureSessionEntity(
            id = session.id,
            sessionUuid = session.sessionUuid,
            connectionId = session.connectionId,
            clientKind = session.clientKind,
            clientVersion = session.clientVersion,
            clientSessionId = session.clientSessionId,
            startedAt = session.startedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByUuid(sessionUuid: UUID): CaptureSession? =
        jpa.findBySessionUuid(sessionUuid)?.toDomain()

    override fun link(sessionId: Long, captureId: Long) {
        if (!linkJpa.existsBySessionIdAndCaptureId(sessionId, captureId)) {
            linkJpa.save(CaptureSessionLinkEntity(sessionId = sessionId, captureId = captureId))
        }
    }

    private fun CaptureSessionEntity.toDomain(): CaptureSession = CaptureSession.reconstitute(
        id = id!!,
        sessionUuid = sessionUuid,
        connectionId = connectionId,
        clientKind = clientKind,
        clientVersion = clientVersion,
        clientSessionId = clientSessionId,
        startedAt = startedAt,
    )
}

@Repository
class IdempotencyReceiptAdapter(
    private val jpa: IdempotencyReceiptJpaRepository,
) : IdempotencyReceiptPort {

    override fun find(scopeKey: String, operation: String, key: String): IdempotencyReceipt? =
        jpa.findByScopeKeyAndOperationAndIdempotencyKey(scopeKey, operation, key)?.toDomain()

    override fun save(receipt: IdempotencyReceipt): IdempotencyReceipt {
        val entity = IdempotencyReceiptEntity(
            scopeKey = receipt.scopeKey,
            operation = receipt.operation,
            idempotencyKey = receipt.key,
            requestHash = receipt.requestHash,
            responseStatus = receipt.responseStatus,
            responseBody = receipt.responseBody,
            createdAt = receipt.createdAt,
        )
        return jpa.save(entity).toDomain()
    }

    private fun IdempotencyReceiptEntity.toDomain(): IdempotencyReceipt = IdempotencyReceipt(
        scopeKey = scopeKey,
        operation = operation,
        key = idempotencyKey,
        requestHash = requestHash,
        responseStatus = responseStatus,
        responseBody = responseBody,
        createdAt = createdAt,
    )
}
