package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant

/**
 * 해결 후보. 최소 1개의 존재하는 Attempt(clientAttemptId)를 참조해야 한다 — 참조 검증은 use case 가
 * 같은 Capture 의 Attempt 집합과 대조한다. clientSolutionId 는 같은 Capture 안에서 유일(UNIQUE).
 */
class CaptureSolutionCandidate private constructor(
    val id: Long?,
    val captureId: Long,
    val clientSolutionId: String,
    var title: String,
    var content: SolutionContent,
    val createdAt: Instant,
) {
    init {
        require(title.isNotBlank()) { "solution title must not be blank" }
        require(content.clientAttemptIds.isNotEmpty()) { "solution must reference at least one attempt" }
    }

    companion object {
        fun create(
            captureId: Long,
            clientSolutionId: String,
            title: String,
            content: SolutionContent,
            now: Instant,
        ): CaptureSolutionCandidate = CaptureSolutionCandidate(
            id = null,
            captureId = captureId,
            clientSolutionId = clientSolutionId,
            title = title,
            content = content,
            createdAt = now,
        )

        fun reconstitute(
            id: Long,
            captureId: Long,
            clientSolutionId: String,
            title: String,
            content: SolutionContent,
            createdAt: Instant,
        ): CaptureSolutionCandidate = CaptureSolutionCandidate(
            id, captureId, clientSolutionId, title, content, createdAt,
        )
    }
}
