package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureSessionRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import java.util.UUID

/**
 * (PR-5 E) 리뷰 백스톱 — 봉인(READY_FOR_REVIEW) 후 뒤늦게 서버에 도착해 Capture 에 붙지 못한
 * **같은 세션의 미연결 이벤트(고아 근거)** 를 조회하고, 사람이 리뷰에서 그 Capture 에 연결한다.
 *
 * 세션 범위로 좁히는 이유: 한 연결(connection)에는 여러 작업 세션이 섞이므로, 이 Capture 가 생성된
 * 세션의 clientSessionId 로만 후보를 제한해 다른 세션 근거가 섞이지 않게 한다. 본인 소유만(아니면 404).
 */
@Service
class ListCaptureOrphanEventsUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val sessions: CaptureSessionRepositoryPort,
    private val events: CaptureEventRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(captureUuid: UUID, ownerUserId: Long): List<CaptureEvent> {
        val capture = captures.findByUuidAndOwner(captureUuid, ownerUserId)
            ?: throw CaptureNotFoundException(captureUuid)
        val clientSessionIds = sessions.clientSessionIdsForCapture(capture.id!!)
        if (clientSessionIds.isEmpty()) return emptyList() // 세션 연결이 없으면 후보도 없음
        return clientSessionIds
            .flatMap { events.findUnlinkedBySession(ownerUserId, capture.originatingConnectionId, it) }
            .distinctBy { it.clientEventId }
    }
}

/**
 * (PR-5 E) 리뷰에서 선택한 고아 이벤트를 이 Capture 에 연결. 사람 인증 경로(에이전트 토큰 아님)라
 * 봉인된 Capture 에도 허용한다 — 단 READY_FOR_REVIEW 에서만(그 외 409). 같은 세션·미연결 이벤트만 붙인다.
 *  - 같은 Capture 재연결은 멱등(linked), 다른 Capture 소속/타 세션/없음은 skipped(가로채지 않음).
 */
@Service
class AttachReviewEventsUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val sessions: CaptureSessionRepositoryPort,
    private val events: CaptureEventRepositoryPort,
) {
    @Transactional
    fun invoke(captureUuid: UUID, ownerUserId: Long, clientEventIds: List<String>): Result {
        val capture = captures.findByUuidAndOwner(captureUuid, ownerUserId)
            ?: throw CaptureNotFoundException(captureUuid)
        capture.requireReviewable()
        val captureId = capture.id!!
        val allowedSessions = sessions.clientSessionIdsForCapture(captureId).toSet()

        val linked = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (clientEventId in clientEventIds.distinct()) {
            val event = events.findByConnectionAndClientId(capture.originatingConnectionId, clientEventId)
            when {
                event == null -> skipped += clientEventId
                event.ownerUserId != ownerUserId -> skipped += clientEventId
                event.captureId == captureId -> linked += clientEventId // 멱등
                event.captureId != null -> skipped += clientEventId // 다른 Capture 소속 — 가로채지 않음
                event.sessionId == null || event.sessionId !in allowedSessions -> skipped += clientEventId // 이 세션 고아만
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
