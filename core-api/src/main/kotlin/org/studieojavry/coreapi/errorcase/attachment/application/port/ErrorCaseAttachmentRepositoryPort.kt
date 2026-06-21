package org.studieojavry.coreapi.errorcase.attachment.application.port

import org.springframework.stereotype.Repository
import org.studieojavry.coreapi.errorcase.attachment.domain.model.Attachment
import java.time.Instant

@Repository
interface ErrorCaseAttachmentRepositoryPort {

    fun save(attachment: Attachment): Attachment
    fun existsByMarkerId(markerId: String): Boolean
    fun findByMarkerId(markerId: String): Attachment?
    fun findAllByMarkerIds(markerIds: List<String>): List<Attachment>

    /** 케이스에 연결된 첨부 전체 (케이스 삭제 cascade 용). */
    fun findAllByErrorCaseId(errorCaseId: Long): List<Attachment>

    /** markerIds 를 케이스에 연결(errorCaseId 세팅). 벌크 UPDATE. */
    fun linkToCase(markerIds: List<String>, errorCaseId: Long)

    /** markerIds 의 케이스 연결 해제(errorCaseId = null → orphan → GC 대상). 벌크 UPDATE. */
    fun unlinkFromCase(markerIds: List<String>)

    /** marker 로 row 삭제. 멱등 — 이미 없으면 no-op. */
    fun deleteByMarkerId(markerId: String)

    /**
     * 미연결(errorCaseId IS NULL) + uploadedAt 가 threshold 이전인 orphan 첨부를 limit 만큼 조회.
     * GC 배치 루프가 사용. (오래된 것부터)
     */
    fun findUnlinkedOlderThan(threshold: Instant, limit: Int): List<Attachment>

    /**
     * 특정 업로더의 미연결(pending) 첨부를 최신순으로 limit 만큼 조회.
     * 작성 화면의 "이번에 올린 첨부" 트레이 재조회용(GET /error-attachments/mine?linked=false).
     * 인덱스: ix_attachment_uploader_orphan (uploaded_by_user_id, error_case_id).
     */
    fun findUnlinkedByUploader(uploaderUserId: Long, limit: Int): List<Attachment>
}
