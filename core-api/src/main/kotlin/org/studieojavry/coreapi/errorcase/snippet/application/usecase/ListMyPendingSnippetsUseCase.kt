package org.studieojavry.coreapi.errorcase.snippet.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import java.time.Instant

/**
 * 작성자 본인의 **미연결(pending) 스니펫** 목록을 반환한다. (첨부 ListMyPendingAttachmentsUseCase 와 대칭)
 *
 * 왜 필요한가: 스니펫도 첨부처럼 케이스보다 먼저 생성되어 markerId 를 받고, 케이스에 연결되지 않으면
 * TTL 후 GC 된다. 작성 화면을 새로고침/복귀하면 방금 만든 스니펫을 다시 볼 방법이 없었다.
 * (케이스에 연결된 스니펫은 케이스 상세 응답이 code 와 함께 내려주므로 여기 대상 아님.)
 *
 * 첨부와 달리 스니펫은 파일이 아니라 DB 텍스트라 presigned URL 이 없다 — code 를 그대로 담아 반환한다.
 */
@Service
class ListMyPendingSnippetsUseCase(
    private val repository: CodeSnippetRepositoryPort,
) {

    @Transactional(readOnly = true)
    fun invoke(uploaderUserId: Long): List<Item> {
        return repository.findUnlinkedByUploader(uploaderUserId, MAX_ITEMS).map {
            Item(
                markerId = it.markerId,
                embedToken = it.embedToken(),
                title = it.title,
                language = it.language,
                filePathOrClass = it.filePathOrClass,
                lineRange = it.lineRange,
                caption = it.caption,
                code = it.code,
                uploadedAt = it.uploadedAt,
            )
        }
    }

    data class Item(
        val markerId: String,
        val embedToken: String,
        val title: String,
        val language: String,
        val filePathOrClass: String?,
        val lineRange: String?,
        val caption: String?,
        val code: String,
        val uploadedAt: Instant,
    )

    companion object {
        const val MAX_ITEMS = 100
    }
}
