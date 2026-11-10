package org.studieojavry.coreapi.agentcapture.infrastructure.jpa.adapter

import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureArtifactRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureAttemptRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSessionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSolutionRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureImportRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceipt
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceiptPort
import org.studieojavry.coreapi.agentcapture.application.port.ProjectBindingRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureImport
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureImportJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.ProjectBindingJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureImportEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.ProjectBindingEntity
import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.AttemptDetail
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureArtifact
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureContent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.SolutionContent
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.AgentCaptureJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureArtifactJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureAttemptJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureSessionJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureSessionLinkJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.CaptureSolutionJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.IdempotencyReceiptJpaRepository
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.AgentCaptureEntity
import org.studieojavry.coreapi.agentcapture.infrastructure.jpa.entity.CaptureArtifactEntity
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
            importedCaseId = capture.importedCaseId,
        )
        // saveAndFlush: @Version(revision) 증가가 flush 시점에 일어나므로, 응답에 새 revision 을
        // 반영하려면 즉시 flush 해야 한다(그래야 멱등 영수증에도 올바른 revision 이 저장된다).
        return jpa.saveAndFlush(entity).toDomain()
    }

    override fun findByUuid(captureUuid: UUID): AgentCapture? =
        jpa.findByCaptureUuid(captureUuid)?.toDomain()

    override fun findByUuidAndOwner(captureUuid: UUID, ownerUserId: Long): AgentCapture? =
        jpa.findByCaptureUuidAndOwnerUserId(captureUuid, ownerUserId)?.toDomain()

    override fun listByOwnerAndStatus(
        ownerUserId: Long,
        status: org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus,
    ): List<AgentCapture> =
        jpa.findByOwnerUserIdAndStatusOrderByUpdatedAtDescIdDesc(ownerUserId, status).map { it.toDomain() }

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
        importedCaseId = importedCaseId,
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
class CaptureArtifactRepositoryAdapter(
    private val jpa: CaptureArtifactJpaRepository,
) : CaptureArtifactRepositoryPort {

    override fun save(artifact: CaptureArtifact): CaptureArtifact {
        val entity = CaptureArtifactEntity(
            id = artifact.id,
            captureId = artifact.captureId,
            clientArtifactId = artifact.clientArtifactId,
            objectKey = artifact.objectKey,
            sha256 = artifact.sha256,
            contentType = artifact.contentType,
            sizeBytes = artifact.sizeBytes,
            fileName = artifact.fileName,
            uploaderUserId = artifact.uploaderUserId,
            status = artifact.status,
            createdAt = artifact.createdAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findByCaptureAndClientId(captureId: Long, clientArtifactId: String): CaptureArtifact? =
        jpa.findByCaptureIdAndClientArtifactId(captureId, clientArtifactId)?.toDomain()

    override fun listByCapture(captureId: Long): List<CaptureArtifact> =
        jpa.findByCaptureIdOrderByIdAsc(captureId).map { it.toDomain() }

    override fun countByCapture(captureId: Long): Int = jpa.countByCaptureId(captureId).toInt()

    override fun totalSizeByCapture(captureId: Long): Long = jpa.totalSizeByCaptureId(captureId)

    override fun completedClientIds(captureId: Long): Set<String> = jpa.completedClientIds(captureId).toSet()

    private fun CaptureArtifactEntity.toDomain(): CaptureArtifact = CaptureArtifact.reconstitute(
        id = id!!,
        captureId = captureId,
        clientArtifactId = clientArtifactId,
        objectKey = objectKey,
        sha256 = sha256,
        contentType = contentType,
        sizeBytes = sizeBytes,
        fileName = fileName,
        uploaderUserId = uploaderUserId,
        status = status,
        createdAt = createdAt,
    )
}

@Repository
class IdempotencyReceiptAdapter(
    private val jpa: IdempotencyReceiptJpaRepository,
) : IdempotencyReceiptPort {

    override fun find(scopeKey: String, operation: String, key: String): IdempotencyReceipt? =
        jpa.findByScopeKeyAndOperationAndIdempotencyKey(scopeKey, operation, key)?.toDomain()

    override fun deleteOlderThan(threshold: java.time.Instant): Int =
        jpa.deleteByCreatedAtBefore(threshold)

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

@Repository
class CaptureImportRepositoryAdapter(
    private val jpa: CaptureImportJpaRepository,
    private val objectMapper: ObjectMapper,
) : CaptureImportRepositoryPort {

    override fun findByCaptureId(captureId: Long): CaptureImport? =
        jpa.findByCaptureId(captureId)?.toDomain()

    override fun save(record: CaptureImport): CaptureImport {
        val entity = CaptureImportEntity(
            captureId = record.captureId,
            reviewedRevision = record.reviewedRevision,
            reviewerUserId = record.reviewerUserId,
            reviewedAt = record.reviewedAt,
            targetCaseId = record.targetCaseId,
            requestHash = record.requestHash,
            attemptStepMap = objectMapper.writeValueAsString(record.attemptStepMap),
            createdAt = record.createdAt,
        )
        return jpa.save(entity).toDomain()
    }

    // attemptStepMap 은 영속 전용(출처 보존) — read-back 소비처가 없어 도메인엔 빈 맵으로 둔다.
    private fun CaptureImportEntity.toDomain(): CaptureImport = CaptureImport(
        id = id,
        captureId = captureId,
        reviewedRevision = reviewedRevision,
        reviewerUserId = reviewerUserId,
        reviewedAt = reviewedAt,
        targetCaseId = targetCaseId,
        requestHash = requestHash,
        attemptStepMap = emptyMap(),
        createdAt = createdAt,
    )
}

@Repository
class ProjectBindingRepositoryAdapter(
    private val jpa: ProjectBindingJpaRepository,
) : ProjectBindingRepositoryPort {

    override fun save(binding: ProjectBinding): ProjectBinding {
        val entity = ProjectBindingEntity(
            id = binding.id,
            ownerUserId = binding.ownerUserId,
            projectKey = binding.projectKey,
            normalizedRepoId = binding.normalizedRepoId,
            storagePolicy = binding.storagePolicy,
            createdAt = binding.createdAt,
            updatedAt = binding.updatedAt,
        )
        return jpa.save(entity).toDomain()
    }

    override fun findById(id: Long): ProjectBinding? = jpa.findById(id).orElse(null)?.toDomain()

    override fun findByOwnerAndKey(ownerUserId: Long, projectKey: String): ProjectBinding? =
        jpa.findByOwnerUserIdAndProjectKey(ownerUserId, projectKey)?.toDomain()

    override fun listByOwner(ownerUserId: Long): List<ProjectBinding> =
        jpa.findByOwnerUserIdOrderByProjectKeyAsc(ownerUserId).map { it.toDomain() }

    private fun ProjectBindingEntity.toDomain(): ProjectBinding = ProjectBinding(
        id = id,
        ownerUserId = ownerUserId,
        projectKey = projectKey,
        normalizedRepoId = normalizedRepoId,
        storagePolicy = storagePolicy,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
