package org.studieojavry.coreapi.errorcase.snippet.application.port

import java.time.Instant
import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.attachment.application.port.ErrorCaseAttachmentRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet


/**
 * 코드 스니펫 독립 저장소. 첨부(ErrorCaseAttachmentRepositoryPort)와 대칭.
 */
@Repository
interface CodeSnippetRepositoryPort {

    fun save(snippet: CodeSnippet): CodeSnippet
    fun existsByMarkerId(markerId: String): Boolean
    fun findByMarkerId(markerId: String): CodeSnippet?
    fun findAllByMarkerIds(markerIds: List<String>): List<CodeSnippet>

    /** 케이스에 연결된 스니펫 전체 (케이스 삭제 cascade 용). */
    fun findAllByErrorCaseId(errorCaseId: Long): List<CodeSnippet>

    /** markerIds 를 케이스에 연결(errorCaseId 세팅). 벌크 UPDATE. */
    fun linkToCase(markerIds: List<String>, errorCaseId: Long)

    /** markerIds 의 케이스 연결 해제(errorCaseId = null → orphan → GC 대상). 벌크 UPDATE. */
    fun unlinkFromCase(markerIds: List<String>)

    /** marker 로 row 삭제. 멱등 — 이미 없으면 no-op. */
    fun deleteByMarkerId(markerId: String)

    /** 미연결(errorCaseId IS NULL) + uploadedAt 가 threshold 이전인 orphan 스니펫을 오래된 순 limit 만큼. */
    fun findUnlinkedOlderThan(threshold: Instant, limit: Int): List<CodeSnippet>

    /**
     * 특정 작성자의 미연결(pending) 스니펫을 최신순으로 limit 만큼 조회.
     * 작성 화면 "추가한 스니펫" 트레이 재조회용(GET /error-snippets/mine?linked=false).
     * 첨부의 findUnlinkedByUploader 와 대칭.
     */
    fun findUnlinkedByUploader(uploaderUserId: Long, limit: Int): List<CodeSnippet>
}
