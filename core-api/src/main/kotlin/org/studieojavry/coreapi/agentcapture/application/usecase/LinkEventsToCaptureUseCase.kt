package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import java.util.UUID

/**
 * 미분류(UNCLASSIFIED) Hook 이벤트를 **OPEN Capture** 에 연결(이슈 #145 §2). 개발 AI 가 맥락을 판단해
 * start_capture/record_attempt 흐름에서 호출한다.
 *
 *  - 봉인(READY_FOR_REVIEW)·반영(IMPORTED) Capture 에는 **조용히 추가하지 않는다** → requireAgentWritable(409).
 *  - 같은 Capture 로 재연결은 멱등(linked). 이미 **다른** Capture 에 묶인 이벤트는 **가로채지 않는다**(skipped).
 *  - 본인 연결의 이벤트만(connectionId 범위). 없으면 skipped.
 */
@Service
class LinkEventsToCaptureUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val events: CaptureEventRepositoryPort,
) {
    @Transactional
    fun invoke(captureUuid: UUID, ownerUserId: Long, connectionId: UUID, clientEventIds: List<String>): Result {
        val capture = captures.findByUuidAndOwner(captureUuid, ownerUserId)
            ?: throw CaptureNotFoundException(captureUuid)
        capture.requireAgentWritable()
        val captureId = capture.id!!

        val linked = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (clientEventId in clientEventIds.distinct()) {
            val event = events.findByConnectionAndClientId(connectionId, clientEventId)
            when {
                event == null -> skipped += clientEventId
                event.captureId == captureId -> linked += clientEventId // 멱등
                event.captureId != null -> skipped += clientEventId // 다른 Capture 소속 — 가로채지 않음
                else -> {
                    events.save(event.copy(captureId = captureId, status = CaptureEventStatus.LINKED))
                    linked += clientEventId
                }
            }
        }
        return Result(linked = linked, skipped = skipped)
    }

    data class Result(val linked: List<String>, val skipped: List<String>)
}
