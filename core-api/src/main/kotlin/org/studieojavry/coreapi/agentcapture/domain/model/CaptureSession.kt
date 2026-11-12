package org.studieojavry.coreapi.agentcapture.domain.model

import java.time.Instant
import java.util.UUID

/**
 * 수집 세션. 한 세션에서 여러 Capture 를, 한 Capture 를 여러 세션에서 이어갈 수 있도록 Session 과
 * Capture 의 식별자를 분리한다(연결은 CaptureSessionLink). sessionUuid 는 재전송 시 변경하지 않는다.
 *
 * projectBindingId: 이 세션이 속한 프로젝트(선택). 생성되는 Capture 가 상속한다(PR-Y). nullable.
 */
class CaptureSession private constructor(
    val id: Long?,
    val sessionUuid: UUID,
    val connectionId: UUID,
    val clientKind: String,
    val clientVersion: String?,
    val clientSessionId: String,
    val startedAt: Instant,
    val projectBindingId: Long? = null,
) {
    /** 재등록(동일 sessionUuid)에서 프로젝트 문맥을 갱신 — last-wins. id 보존해 save 가 update 되게. */
    fun withProjectBinding(projectBindingId: Long?): CaptureSession = CaptureSession(
        id, sessionUuid, connectionId, clientKind, clientVersion, clientSessionId, startedAt, projectBindingId,
    )

    companion object {
        fun register(
            sessionUuid: UUID,
            connectionId: UUID,
            clientKind: String,
            clientVersion: String?,
            clientSessionId: String,
            now: Instant,
            projectBindingId: Long? = null,
        ): CaptureSession = CaptureSession(
            id = null,
            sessionUuid = sessionUuid,
            connectionId = connectionId,
            clientKind = clientKind,
            clientVersion = clientVersion,
            clientSessionId = clientSessionId,
            startedAt = now,
            projectBindingId = projectBindingId,
        )

        fun reconstitute(
            id: Long,
            sessionUuid: UUID,
            connectionId: UUID,
            clientKind: String,
            clientVersion: String?,
            clientSessionId: String,
            startedAt: Instant,
            projectBindingId: Long? = null,
        ): CaptureSession = CaptureSession(
            id, sessionUuid, connectionId, clientKind, clientVersion, clientSessionId, startedAt, projectBindingId,
        )
    }
}
