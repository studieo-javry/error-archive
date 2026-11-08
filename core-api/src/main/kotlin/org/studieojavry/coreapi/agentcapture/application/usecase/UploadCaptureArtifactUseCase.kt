package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureArtifactRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.ArtifactHashMismatchException
import org.studieojavry.coreapi.agentcapture.domain.model.ArtifactTooLargeException
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureArtifact
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * agent 전용 근거 첨부 업로드. 기존 스토리지 포트(S3)를 재사용하되 Capture 소유권·멱등·한도를 더한다.
 *
 * 멱등: (captureId, clientArtifactId) 로 조회 — 같은 해시면 기존 첨부 반환(중복 업로드 무해),
 *       다른 해시면 409. 형식·크기는 서버에서 재검사한다(로컬 경로 문자열만 보내는 것 금지).
 */
@Service
class UploadCaptureArtifactUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val artifacts: CaptureArtifactRepositoryPort,
    private val storage: AttachmentStoragePort,
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val validator: ArtifactValidator,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun invoke(cmd: UploadArtifactCommand): Outcome {
        val capture = captures.findByUuidAndOwner(cmd.captureUuid, cmd.ownerUserId)
            ?: throw CaptureNotFoundException(cmd.captureUuid)
        capture.requireAgentWritable()
        val captureId = capture.id!!

        val sha256 = sha256Hex(cmd.bytes)

        // 멱등: 같은 clientArtifactId
        artifacts.findByCaptureAndClientId(captureId, cmd.clientArtifactId)?.let { existing ->
            if (existing.sha256 == sha256) return Outcome(existing, deduplicated = true)
            throw ArtifactHashMismatchException(cmd.clientArtifactId)
        }

        // 한도는 형식 검사(파싱)보다 먼저 — 큰 파일을 디코드/파싱하지 않고 바로 거른다.
        enforceLimits(captureId, cmd.bytes.size.toLong())
        val contentType = validator.validate(cmd.declaredContentType, cmd.bytes)

        val markerId = markerIdGenerator.generateAttachmentMarkerId()
        val objectKey = objectKeyFor(markerId)
        storage.put(objectKey, cmd.bytes, contentType)

        val saved = artifacts.save(
            CaptureArtifact.completed(
                captureId = captureId,
                clientArtifactId = cmd.clientArtifactId,
                objectKey = objectKey,
                sha256 = sha256,
                contentType = contentType,
                sizeBytes = cmd.bytes.size.toLong(),
                fileName = cmd.fileName,
                uploaderUserId = cmd.ownerUserId,
                now = Instant.now(clock),
            )
        )
        return Outcome(saved, deduplicated = false)
    }

    private fun enforceLimits(captureId: Long, newSize: Long) {
        if (newSize > MAX_FILE_BYTES) {
            throw ArtifactTooLargeException("file exceeds ${MAX_FILE_BYTES / (1024 * 1024)} MiB limit")
        }
        if (artifacts.countByCapture(captureId) >= MAX_FILES_PER_CAPTURE) {
            throw ArtifactTooLargeException("capture reached max $MAX_FILES_PER_CAPTURE artifacts")
        }
        if (artifacts.totalSizeByCapture(captureId) + newSize > MAX_TOTAL_BYTES) {
            throw ArtifactTooLargeException("capture exceeds ${MAX_TOTAL_BYTES / (1024 * 1024)} MiB total limit")
        }
    }

    private fun objectKeyFor(markerId: String): String {
        val shard = markerId.take(2).ifBlank { "00" }
        return "agent-artifacts/$shard/$markerId"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    data class Outcome(val artifact: CaptureArtifact, val deduplicated: Boolean)

    companion object {
        const val MAX_FILE_BYTES = 10L * 1024 * 1024        // 10 MiB
        const val MAX_FILES_PER_CAPTURE = 20
        const val MAX_TOTAL_BYTES = 50L * 1024 * 1024       // 50 MiB
    }
}

data class UploadArtifactCommand(
    val captureUuid: UUID,
    val ownerUserId: Long,
    val clientArtifactId: String,
    val declaredContentType: String,
    val fileName: String?,
    val bytes: ByteArray,
)
