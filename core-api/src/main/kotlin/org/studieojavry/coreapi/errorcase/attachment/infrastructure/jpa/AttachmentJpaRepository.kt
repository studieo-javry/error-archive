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

    /**
     * 벌크 DELETE (즉시 SQL). 파생 delete(em.remove) 대신 이걸 쓰는 이유:
     * 케이스 cascade 삭제(DeleteErrorCaseUseCase)에서 이후 `@Modifying(clearAutomatically=true)`
     * bulk delete 들이 영속성 컨텍스트를 clear 하면 pending em.remove 가 유실돼 row 가 남는 버그가 있었다.
     * bulk delete 는 컨텍스트와 무관하게 즉시 실행돼 안전. 멱등(없으면 0 rows).
     */
    @Modifying
    @Query("DELETE FROM AttachmentEntity a WHERE a.markerId = :markerId")
    fun deleteByMarkerId(@Param("markerId") markerId: String)

    // 미연결 + 오래된 orphan 을 오래된 순으로. 페이징(limit)은 Pageable 로 전달.
    fun findByErrorCaseIdIsNullAndUploadedAtBeforeOrderByUploadedAtAsc(
        threshold: Instant,
        pageable: Pageable,
    ): List<AttachmentEntity>

    // 업로더의 미연결(pending) 첨부 최신순. "이번에 올린 첨부" 트레이 재조회용.
    fun findByErrorCaseIdIsNullAndUploadedByUserIdOrderByUploadedAtDesc(
        uploadedByUserId: Long,
        pageable: Pageable,
    ): List<AttachmentEntity>

    @Modifying(clearAutomatically = true)
    @Query("UPDATE AttachmentEntity a SET a.errorCaseId = :caseId WHERE a.markerId IN :markerIds")
    fun linkToCase(@Param("markerIds") markerIds: List<String>, @Param("caseId") caseId: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("UPDATE AttachmentEntity a SET a.errorCaseId = null WHERE a.markerId IN :markerIds")
    fun unlinkFromCase(@Param("markerIds") markerIds: List<String>): Int
}