package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.AgentCaptureRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureNotFoundException
import java.util.UUID

/**
 * 이 Capture 에 연결된(LINKED) 수집 근거 조회 — 검토 화면에서 사람이 "이미 이 기록에 귀속된 근거"를 되비추는 경로.
 * #205 는 미연결 후보만 노출했을 뿐 연결된 이벤트를 되읽는 경로가 없어, 연결 후 사용자가 아무것도 확인할 수 없었다.
 * 본인 소유만(아니면 404). id DESC + keyset(cursorId) 로 과거까지 잇는다. (AI 연결·사용자 연결 모두 LINKED 라 함께 노출.)
 */
@Service
class ListCaptureLinkedEventsUseCase(
    private val captures: AgentCaptureRepositoryPort,
    private val events: CaptureEventRepositoryPort,
) {
    /** 한 페이지 결과. nextCursor 를 다음 조회의 cursor 로 넘기면 더 오래된 이벤트를 잇는다. */
    data class Page(val items: List<CaptureEvent>, val nextCursor: Long?, val hasNext: Boolean)

    @Transactional(readOnly = true)
    fun invoke(captureUuid: UUID, ownerUserId: Long, cursorId: Long?, limit: Int): Page {
        val capture = captures.findByUuidAndOwner(captureUuid, ownerUserId)
            ?: throw CaptureNotFoundException(captureUuid)
        val capped = limit.coerceIn(1, MAX_LIMIT)
        // limit+1 로 한 건 더 조회해 다음 페이지 존재 여부(hasNext)를 판정.
        val rows = events.findLinkedByCapture(capture.id!!, ownerUserId, cursorId, capped + 1)
        val hasNext = rows.size > capped
        val items = if (hasNext) rows.take(capped) else rows
        return Page(items = items, nextCursor = if (hasNext) items.last().id else null, hasNext = hasNext)
    }

    private companion object {
        const val MAX_LIMIT = 100
    }
}
