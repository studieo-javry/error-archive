package org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa.adapter

import java.time.Instant
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.adapter.AttachmentRepositoryAdapter
import org.studieojavry.coreapi.errorcase.case.infrastructure.jpa.adapter.ErrorCaseRepositoryAdapter
import org.studieojavry.coreapi.errorcase.snippet.application.port.CodeSnippetRepositoryPort
import org.studieojavry.coreapi.errorcase.snippet.domain.model.CodeSnippet
import org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa.CodeSnippetJpaRepository
import org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa.entity.CodeSnippetEntity


/**
 * 코드 스니펫 독립 저장 어댑터. 첨부(AttachmentRepositoryAdapter)와 대칭.
 * 케이스 연결(errorCaseId 세팅)은 ErrorCaseRepositoryAdapter 가 marker 로 찾아 갱신한다.
 */
@Component
class CodeSnippetRepositoryAdapter(
    private val jpa: CodeSnippetJpaRepository
) : CodeSnippetRepositoryPort {

    override fun save(snippet: CodeSnippet): CodeSnippet {
        val entity = jpa.findByMarkerId(snippet.markerId)?.also {
            it.title = snippet.title
            it.language = snippet.language
            it.filePathOrClass = snippet.filePathOrClass
            it.lineRange = snippet.lineRange
            it.caption = snippet.caption
            it.code = snippet.code
            // uploadedByUserId / uploadedAt 은 생성 시점 고정 — 갱신하지 않음
        } ?: CodeSnippetEntity(
            markerId = snippet.markerId,
            title = snippet.title,
            language = snippet.language,
            filePathOrClass = snippet.filePathOrClass,
            lineRange = snippet.lineRange,
            caption = snippet.caption,
            code = snippet.code,
            uploadedByUserId = snippet.uploadedByUserId,
            uploadedAt = snippet.uploadedAt
        )
        return jpa.save(entity).toDomain()
    }

    override fun existsByMarkerId(markerId: String): Boolean = jpa.existsByMarkerId(markerId)

    override fun findByMarkerId(markerId: String): CodeSnippet? =
        jpa.findByMarkerId(markerId)?.toDomain()

    override fun findAllByMarkerIds(markerIds: List<String>): List<CodeSnippet> =
        if (markerIds.isEmpty()) emptyList()
        else jpa.findAllByMarkerIdIn(markerIds).map { it.toDomain() }

    override fun findAllByErrorCaseId(errorCaseId: Long): List<CodeSnippet> =
        jpa.findAllByErrorCaseId(errorCaseId).map { it.toDomain() }

    override fun linkToCase(markerIds: List<String>, errorCaseId: Long) {
        if (markerIds.isNotEmpty()) jpa.linkToCase(markerIds, errorCaseId)
    }

    override fun unlinkFromCase(markerIds: List<String>) {
        if (markerIds.isNotEmpty()) jpa.unlinkFromCase(markerIds)
    }

    override fun deleteByMarkerId(markerId: String) = jpa.deleteByMarkerId(markerId)

    override fun findUnlinkedOlderThan(threshold: Instant, limit: Int): List<CodeSnippet> =
        jpa.findByErrorCaseIdIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(threshold, PageRequest.of(0, limit))
            .map { it.toDomain() }

    private fun CodeSnippetEntity.toDomain(): CodeSnippet = CodeSnippet(
        markerId = markerId,
        title = title,
        language = language,
        filePathOrClass = filePathOrClass,
        lineRange = lineRange,
        caption = caption,
        code = code,
        uploadedByUserId = uploadedByUserId,
        uploadedAt = uploadedAt,
        errorCaseId = errorCaseId
    )
}
