package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
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
        // 행 락으로 Complete·Discard 와 직렬화(OPEN 연결이 봉인/폐기와 인터리브되지 않도록). 이벤트 자체 경합은 claim 이 막는다.
        val capture = captures.findByUuidAndOwnerForUpdate(captureUuid, ownerUserId)
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
                    // 조건부 원자 연결(captureId IS NULL & UNCLASSIFIED) — 같은 세션에 Capture 여러 개가 동시에
                    // 같은 이벤트를 연결하려는 경합을 DB WHERE 로 차단. 0행이면 재조회로 멱등/선점 구분.
                    if (events.claim(connectionId, ownerUserId, clientEventId, captureId) == 1) {
                        linked += clientEventId
                    } else {
                        val now = events.findByConnectionAndClientId(connectionId, clientEventId)
                        if (now?.captureId == captureId) linked += clientEventId else skipped += clientEventId
                    }
                }
            }
        }
        return Result(linked = linked, skipped = skipped)
    }

    data class Result(val linked: List<String>, val skipped: List<String>)
}
