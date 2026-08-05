package org.studieojavry.coreapi.errorcase.attachment.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.attachment.config.PresignTtlProperties
import java.time.Instant

/**
 * 업로더 본인의 **미연결(pending) 첨부** 목록을 fresh presigned inline URL 과 함께 반환한다.
 *
 * 왜 필요한가: 업로드 응답의 previewUrl 은 1회성(짧은 TTL)이라, 사용자가 작성 화면을 새로고침하거나
 * 다시 돌아오면 "이번에 올린 첨부"를 다시 볼 방법이 없었다. 이 use case 가 그 재조회를 담당한다.
 * (케이스에 연결된 첨부는 케이스 상세 응답이 presigned URL 로 내려주므로 여기 대상 아님.)
 *
 * 보안: uploaderUserId 로 필터 → 본인 첨부만. TTL 은 restricted(짧게) — 업로더 전용 미리보기.
 */
@Service
class ListMyPendingAttachmentsUseCase(
    private val repository: ErrorCaseAttachmentRepositoryPort,
    private val presigner: AttachmentPresigner,
    private val presignTtl: PresignTtlProperties,
) {

    @Transactional(readOnly = true)
    fun invoke(uploaderUserId: Long): List<Item> {
        val pending = repository.findUnlinkedByUploader(uploaderUserId, MAX_ITEMS)
        return pending.map {
            Item(
                markerId = it.markerId,
                embedToken = it.embedToken(),
                fileName = it.fileName,
                contentType = it.contentType,
                size = it.size,
                kind = it.kind.name,
                title = it.title,
                caption = it.caption,
                previewUrl = presigner.viewUrl(it.objectKey, presignTtl.restrictedTtl),
                uploadedAt = it.uploadedAt,
            )
        }
    }

    data class Item(
        val markerId: String,
        val embedToken: String,
        val fileName: String,
        val contentType: String,
        val size: Long,
        val kind: String,
        val title: String?,
        val caption: String?,
        /** 미리보기용 presigned inline URL (짧은 TTL, 업로더 전용). */
        val previewUrl: String,
        val uploadedAt: Instant,
    )

    companion object {
        /** 한 사용자가 동시에 작성 중 올릴 pending 첨부 상한(방어적). */
        const val MAX_ITEMS = 100
    }
}
