package org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.snippet.infrastructure.jpa.entity.CodeSnippetEntity
import java.time.Instant

interface CodeSnippetJpaRepository : JpaRepository<CodeSnippetEntity, Long> {
    fun findAllByErrorCaseId(errorCaseId: Long): List<CodeSnippetEntity>
    fun findByMarkerId(markerId: String): CodeSnippetEntity?
    fun findAllByMarkerIdIn(markerIds: List<String>): List<CodeSnippetEntity>
    fun existsByMarkerId(markerId: String): Boolean

    /** 벌크 DELETE (즉시 SQL) — cascade 삭제 시 후속 clearAutomatically 로 유실되지 않게. [AttachmentJpaRepository.deleteByMarkerId] 참고. */
    @Modifying
    @Query("DELETE FROM CodeSnippetEntity s WHERE s.markerId = :markerId")
    fun deleteByMarkerId(@Param("markerId") markerId: String)

    fun findByErrorCaseIdIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(
        threshold: Instant,
        pageable: Pageable,
    ): List<CodeSnippetEntity>

    // 작성자의 미연결(pending) 스니펫 최신순. "추가한 스니펫" 트레이 재조회용.
    fun findByErrorCaseIdIsNullAndUploadedByUserIdOrderByUploadedAtDesc(
        uploadedByUserId: Long,
        pageable: Pageable,
    ): List<CodeSnippetEntity>

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CodeSnippetEntity s SET s.errorCaseId = :caseId WHERE s.markerId IN :markerIds")
    fun linkToCase(@Param("markerIds") markerIds: List<String>, @Param("caseId") caseId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CodeSnippetEntity s SET s.errorCaseId = null WHERE s.markerId IN :markerIds")
    fun unlinkFromCase(@Param("markerIds") markerIds: List<String>): Int
}