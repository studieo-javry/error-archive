package org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.studieojavry.coreapi.errorcase.attachment.infrastructure.jpa.entity.AttachmentEntity
import java.time.Instant

interface AttachmentJpaRepository : JpaRepository<AttachmentEntity, Long> {
    fun findByMarkerId(markerId: String): AttachmentEntity?
    fun findAllByMarkerIdIn(markerIds: List<String>): List<AttachmentEntity>
    fun findAllByErrorCaseId(errorCaseId: Long): List<AttachmentEntity>
    fun existsByMarkerId(markerId: String): Boolean
    fun deleteByMarkerId(markerId: String)

    // 미연결 + 오래된 orphan 을 오래된 순으로. 페이징(limit)은 Pageable 로 전달.
    fun findByErrorCaseIdIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(
        threshold: Instant,
        pageable: Pageable,
    ): List<AttachmentEntity>

    @Modifying(clearAutomatically = true)
    @Query("UPDATE AttachmentEntity a SET a.errorCaseId = :caseId WHERE a.markerId IN :markerIds")
    fun linkToCase(@Param("markerIds") markerIds: List<String>, @Param("caseId") caseId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("UPDATE AttachmentEntity a SET a.errorCaseId = null WHERE a.markerId IN :markerIds")
    fun unlinkFromCase(@Param("markerIds") markerIds: List<String>): Int
}