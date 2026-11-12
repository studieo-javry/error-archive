package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant

/**
 * Capture 의 근거 첨부. 기존 스토리지 포트(S3)를 재사용하되 Capture 소유권·멱등성·수명주기를 더한다.
 * 기존 error_case_attachment 와 별도 테이블 — 고아 GC 가 capture 참조 파일을 지우지 않게 한다.
 *
 * 수명주기: COMPLETED(업로드·저장 완료) → LINKED(검토 반영 시 Case 마커에 연결, PR4).
 * (RESERVED 는 presigned 2단계 업로드용 예약 상태 — P0 동기 업로드에선 쓰지 않는다.)
 */
class CaptureArtifact private constructor(
    val id: Long?,
    val captureId: Long,
    val clientArtifactId: String,
    val objectKey: String,
    val sha256: String,
    val contentType: String,
    val sizeBytes: Long,
    val fileName: String?,
    val uploaderUserId: Long,
    var status: ArtifactStatus,
    val createdAt: Instant,
) {
    val isCompleted: Boolean get() = status == ArtifactStatus.COMPLETED || status == ArtifactStatus.LINKED

    /** 검토 반영 시 Case 근거로 승격·연결됨을 표시(COMPLETED→LINKED). */
    fun markLinked() {
        status = ArtifactStatus.LINKED
    }

    companion object {
        fun completed(
            captureId: Long,
            clientArtifactId: String,
            objectKey: String,
            sha256: String,
            contentType: String,
            sizeBytes: Long,
            fileName: String?,
            uploaderUserId: Long,
            now: Instant,
        ): CaptureArtifact = CaptureArtifact(
            id = null,
            captureId = captureId,
            clientArtifactId = clientArtifactId,
            objectKey = objectKey,
            sha256 = sha256,
            contentType = contentType,
            sizeBytes = sizeBytes,
            fileName = fileName,
            uploaderUserId = uploaderUserId,
            status = ArtifactStatus.COMPLETED,
            createdAt = now,
        )

        fun reconstitute(
            id: Long,
            captureId: Long,
            clientArtifactId: String,
            objectKey: String,
            sha256: String,
            contentType: String,
            sizeBytes: Long,
            fileName: String?,
            uploaderUserId: Long,
            status: ArtifactStatus,
            createdAt: Instant,
        ): CaptureArtifact = CaptureArtifact(
            id, captureId, clientArtifactId, objectKey, sha256, contentType,
            sizeBytes, fileName, uploaderUserId, status, createdAt,
        )
    }
}

enum class ArtifactStatus { RESERVED, COMPLETED, LINKED }
