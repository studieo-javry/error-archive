package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.studieojavry.coreapi.errorcase.attachment.application.command.CreateAttachmentCommand
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.config.PresignTtlProperties
import org.studieojavry.coreapi.errorcase.shared.application.port.MarkerIdGeneratorPort
import org.studieojavry.coreapi.errorcase.attachment.domain.model.vo.AttachmentKind
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import java.time.Instant

@Service
class CreateAttachmentUseCase(
    private val markerIdGenerator: MarkerIdGeneratorPort,
    private val attachmentStoragePort: AttachmentStoragePort,
    private val errorCaseAttachmentRepositoryPort: ErrorCaseAttachmentRepositoryPort,
    private val presigner: AttachmentPresigner,
    private val presignTtl: PresignTtlProperties,
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun invoke(command: CreateAttachmentCommand): Result {

        val markerId = markerIdGenerator.generateAttachmentMarkerId()
        val objectKey = objectKeyFor(markerId)
        val kind = resolveKind(command.contentType)

        attachmentStoragePort.put(
            objectKey = objectKey,
            bytes = command.bytes,
            contentType = command.contentType,
        )

        // 롤백 정리: 트랜잭션이 롤백되면 방금 올린 스토리지 객체를 삭제한다. (DB row 는 롤백돼 사라지므로
        // PurgeOrphan(미링크 row 기준)이 못 잡는 "row 없는 스토리지 고아"를 방지. import 처럼 더 큰 트랜잭션
        // 안에서 호출돼 뒤 단계가 실패하는 경우가 핵심. put 멱등 삭제라 재시도·중복에 안전.)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        // best-effort — 실패 시 삼키되 WARN 으로 가시화한다. 실패하면 DB row 없는
                        // 스토리지 고아가 남고, 이는 row 기준인 PurgeOrphan/백로그 게이지로는 보이지
                        // 않는다(수집 불가·비용 문제, 정합성 아님). Loki 로 추세를 보고, 누적이
                        // 유의미해지면 스토리지↔DB 조정 스윕(attachment-lifecycle.md 참고)으로 회수.
                        runCatching { attachmentStoragePort.delete(objectKey) }
                            .onFailure { ex ->
                                log.warn(ex) { "[attachment] rollback cleanup failed, orphan object remains objectKey=$objectKey" }
                            }
                    }
                }
            })
        }

        val attachment = Attachment(
            markerId = markerId,
            title = command.title,
            caption = command.caption,
            fileName = command.fileName,
            contentType = command.contentType,
            size = command.size,
            kind = kind,
            objectKey = objectKey,
            uploadedByUserId = command.uploaderUserId,
            uploadedAt = Instant.now()
        )

        val saved = errorCaseAttachmentRepositoryPort.save(attachment)

        // 업로드 직후(케이스 링크 前) 위저드 미리보기용 presigned URL — 업로더에게만 노출. 짧은 TTL.
        val previewUrl = presigner.viewUrl(saved.objectKey, presignTtl.restrictedTtl)

        return Result(
            markerId = saved.markerId,
            embedToken = saved.embedToken(),
            fileName = saved.fileName,
            contentType = saved.contentType,
            size = saved.size,
            kind = saved.kind.name,
            previewUrl = previewUrl,
        )
    }

    /** 스토리지 오브젝트 키. markerId 앞 2자로 샤딩. */
    private fun objectKeyFor(markerId: String): String {
        val shard = markerId.take(2).ifBlank { "00" }
        return "attachments/$shard/$markerId"
    }

    private fun resolveKind(contentType: String): AttachmentKind {
        return when {
            contentType.startsWith("image") -> AttachmentKind.IMAGE
            contentType == "application/json" -> AttachmentKind.JSON
            contentType.startsWith("text/") -> AttachmentKind.TEXT
            contentType == "application/pdf" -> AttachmentKind.DOCUMENT
            else -> AttachmentKind.OTHER
        }
    }

    data class Result(
        val markerId: String,
        val embedToken: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val kind: String,
        /** 업로드 직후 미리보기용 presigned inline URL (짧은 TTL). */
        val previewUrl: String,
    )
}
