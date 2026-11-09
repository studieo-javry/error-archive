package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 자동 수집 초안의 집합체 루트. 기존 ErrorCase 와 분리된 별도 구조로 저장한다
 * (ErrorCase.status 에 DRAFT 를 추가하지 않는다 — 미검토 기록이 기존 알림·검색·통계에 섞이지 않게).
 *
 * [revision] 은 엔티티의 @Version 으로 관리된다(낙관 잠금). 에이전트/사용자는 expectedRevision 으로
 * 검사하고, 저장 시 Hibernate 가 증가시킨다. Attempt·Solution 은 captureId 로 참조하는 별도 집합체.
 */
class AgentCapture private constructor(
    val id: Long?,
    val captureUuid: UUID,
    val ownerUserId: Long,
    val originatingConnectionId: UUID,
    var status: CaptureStatus,
    val revision: Int,
    var title: String?,
    var project: String?,
    var content: CaptureContent,
    val createdAt: Instant,
    var updatedAt: Instant,
    var completedAt: Instant?,
    /** 반영(import) 성공 시 생성된 ErrorCase 의 id. IMPORTED 상태에서만 non-null. */
    var importedCaseId: Long?,
) {
    /** 에이전트 쓰기(수정/시도/해결/봉인)는 OPEN 에서만. 봉인/종료 후엔 409. */
    fun requireAgentWritable() {
        if (!status.acceptsAgentWrites) throw CaptureSealedException(captureUuid)
    }

    /** expectedRevision 이 주어졌고 현재와 다르면 409. */
    fun requireRevision(expected: Int?) {
        if (expected != null && expected != revision) {
            throw RevisionConflictException(captureUuid, expected, revision)
        }
    }

    fun edit(title: String?, project: String?, content: CaptureContent, now: Instant) {
        requireAgentWritable()
        this.title = title
        this.project = project
        this.content = content
        this.updatedAt = now
    }

    /**
     * OPEN → READY_FOR_REVIEW 로 봉인. 문제 해결 여부와 무관(미해결도 봉인 가능).
     * 이미 봉인/종료면 409(CAPTURE_SEALED).
     */
    fun complete(now: Instant) {
        requireAgentWritable()
        this.status = CaptureStatus.READY_FOR_REVIEW
        this.completedAt = now
        this.updatedAt = now
    }

    /**
     * 사용자 검토 확정 → 반영 완료(IMPORTED). 봉인된(READY_FOR_REVIEW) 초안에서만.
     * OPEN(미봉인)·DISCARDED 는 CAPTURE_NOT_REVIEWABLE. IMPORTED 재시도는 use case 가 멱등 처리한다.
     */
    fun markImported(caseId: Long, now: Instant) {
        if (status != CaptureStatus.READY_FOR_REVIEW) {
            throw CaptureNotReviewableException(captureUuid, status)
        }
        this.status = CaptureStatus.IMPORTED
        this.importedCaseId = caseId
        this.updatedAt = now
    }

    /**
     * 사용자 폐기 → DISCARDED. OPEN·READY_FOR_REVIEW 에서 가능, 이미 DISCARDED 면 멱등(no-op),
     * IMPORTED(반영 완료)는 폐기 불가 → CAPTURE_ALREADY_IMPORTED.
     */
    fun discard(now: Instant) {
        when (status) {
            CaptureStatus.OPEN, CaptureStatus.READY_FOR_REVIEW -> {
                this.status = CaptureStatus.DISCARDED
                this.updatedAt = now
            }
            CaptureStatus.DISCARDED -> Unit
            CaptureStatus.IMPORTED -> throw CaptureAlreadyImportedException(captureUuid)
        }
    }

    companion object {
        fun start(
            captureUuid: UUID,
            ownerUserId: Long,
            originatingConnectionId: UUID,
            title: String?,
            project: String?,
            content: CaptureContent,
            now: Instant,
        ): AgentCapture = AgentCapture(
            id = null,
            captureUuid = captureUuid,
            ownerUserId = ownerUserId,
            originatingConnectionId = originatingConnectionId,
            status = CaptureStatus.OPEN,
            revision = 0,
            title = title,
            project = project,
            content = content,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
            importedCaseId = null,
        )

        fun reconstitute(
            id: Long,
            captureUuid: UUID,
            ownerUserId: Long,
            originatingConnectionId: UUID,
            status: CaptureStatus,
            revision: Int,
            title: String?,
            project: String?,
            content: CaptureContent,
            createdAt: Instant,
            updatedAt: Instant,
            completedAt: Instant?,
            importedCaseId: Long?,
        ): AgentCapture = AgentCapture(
            id, captureUuid, ownerUserId, originatingConnectionId, status, revision,
            title, project, content, createdAt, updatedAt, completedAt, importedCaseId,
        )
    }
}
