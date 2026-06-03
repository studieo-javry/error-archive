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
    fun deleteByMarkerId(markerId: String)
    fun findByErrorCaseIdIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(
        threshold: Instant,
        pageable: Pageable,
    ): List<CodeSnippetEntity>

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CodeSnippetEntity s SET s.errorCaseId = :caseId WHERE s.markerId IN :markerIds")
    fun linkToCase(@Param("markerIds") markerIds: List<String>, @Param("caseId") caseId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("UPDATE CodeSnippetEntity s SET s.errorCaseId = null WHERE s.markerId IN :markerIds")
    fun unlinkFromCase(@Param("markerIds") markerIds: List<String>): Int
}