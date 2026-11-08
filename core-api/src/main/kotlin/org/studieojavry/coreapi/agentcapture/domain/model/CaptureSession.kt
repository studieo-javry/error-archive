package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 수집 세션. 한 세션에서 여러 Capture 를, 한 Capture 를 여러 세션에서 이어갈 수 있도록 Session 과
 * Capture 의 식별자를 분리한다(연결은 CaptureSessionLink). sessionUuid 는 재전송 시 변경하지 않는다.
 */
class CaptureSession private constructor(
    val id: Long?,
    val sessionUuid: UUID,
    val connectionId: UUID,
    val clientKind: String,
    val clientVersion: String?,
    val clientSessionId: String,
    val startedAt: Instant,
) {
    companion object {
        fun register(
            sessionUuid: UUID,
            connectionId: UUID,
            clientKind: String,
            clientVersion: String?,
            clientSessionId: String,
            now: Instant,
        ): CaptureSession = CaptureSession(
            id = null,
            sessionUuid = sessionUuid,
            connectionId = connectionId,
            clientKind = clientKind,
            clientVersion = clientVersion,
            clientSessionId = clientSessionId,
            startedAt = now,
        )

        fun reconstitute(
            id: Long,
            sessionUuid: UUID,
            connectionId: UUID,
            clientKind: String,
            clientVersion: String?,
            clientSessionId: String,
            startedAt: Instant,
        ): CaptureSession = CaptureSession(
            id, sessionUuid, connectionId, clientKind, clientVersion, clientSessionId, startedAt,
        )
    }
}
