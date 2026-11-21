package org.studieojavry.coreapi.agentcapture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.agentcapture.application.port.CaptureEventRepositoryPort
import org.studieojavry.coreapi.agentcapture.application.usecase.EventResultType
import org.studieojavry.coreapi.agentcapture.application.usecase.IngestEventItem
import org.studieojavry.coreapi.agentcapture.application.usecase.IngestEventsUseCase
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import java.time.Instant
import java.util.UUID

/**
 * 서버 안전망: 커넥터 자기 도구 호출(mcp__error-archive__*) 이벤트는 저장하지 않고 REJECTED.
 * 저장 안 되므로 UNCLASSIFIED 로 쌓이지 않아 capture_complete 봉인 완결성(unseenOnServer)을 막지 않는다.
 * (DB 불요 — self-referential 단락은 레포 호출 전에 반환하므로 가짜 포트로 충분.)
 */
class IngestEventsSelfReferentialTest {

    private class FakePort : CaptureEventRepositoryPort {
        var saveCalled = false
        override fun findByConnectionAndClientId(connectionId: UUID, clientEventId: String): CaptureEvent? = null
        override fun save(event: CaptureEvent): CaptureEvent {
            saveCalled = true
            return event
        }
        override fun findCandidateEvents(
            ownerUserId: Long,
            connectionId: UUID,
            status: CaptureEventStatus,
            sessionId: String?,
            cursorId: Long?,
            limit: Int,
        ): List<CaptureEvent> = emptyList()
        override fun findUnlinkedBySession(ownerUserId: Long, connectionId: UUID, sessionId: String): List<CaptureEvent> = emptyList()
    }

    private fun item(eventType: String) = IngestEventItem(
        clientEventId = "e-${UUID.randomUUID()}",
        eventType = eventType,
        localSequence = 1,
        observedAt = Instant.parse("2026-11-22T00:00:00Z"),
        redactedPayloadJson = "{}",
    )

    @Test
    fun `self-referential 커넥터 도구 이벤트는 저장 없이 REJECTED`() {
        val port = FakePort()
        val out = IngestEventsUseCase(port).invoke(
            UUID.randomUUID(), 1L,
            listOf(item("PostToolUse:mcp__error-archive__capture_start")),
        )
        assertEquals(EventResultType.REJECTED, out[0].result)
        assertFalse(port.saveCalled, "self-referential 이벤트는 저장되면 안 된다")
    }

    @Test
    fun `일반 도구 이벤트는 정상 저장(ACCEPTED)`() {
        val port = FakePort()
        val out = IngestEventsUseCase(port).invoke(
            UUID.randomUUID(), 1L,
            listOf(item("PostToolUse:Bash")),
        )
        assertEquals(EventResultType.ACCEPTED, out[0].result)
        assertTrue(port.saveCalled)
    }
}
