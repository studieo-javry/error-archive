package org.studieojavry.coreapi.errorcase.github.domain.model

import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubConnectionStatus
import org.studieojavry.coreapi.errorcase.github.domain.model.vo.GithubSpace
import java.time.LocalDateTime

/**
 * 설치 1건을 한 공간(개인/워크스페이스)에 묶는 연결. 같은 설치가 여러 공간에 각각 연결될 수 있다.
 *
 * - [connectionId] 는 영속 전 null(IDENTITY).
 * - [connectedByUserId] 는 "누가 연결했나" 감사용 — 개인 공간의 주인은 공간 자체(ownerUserId)이고,
 *   워크스페이스 연결의 주체는 워크스페이스다. 최초 연결자가 팀을 떠나도 연결 소유는 공간에 남는다.
 * - "어느 레포를 사용 선택했나" 는 github_connection_repository(조인 테이블)로 별도 관리.
 */
class GithubConnection private constructor(
    val connectionId: Long?,
    val installationId: Long,
    val space: GithubSpace,
    val connectedByUserId: Long,
    var status: GithubConnectionStatus,
    val createdAt: LocalDateTime,
    var updatedAt: LocalDateTime,
) {
    val isActive: Boolean get() = status == GithubConnectionStatus.ACTIVE

    /** 공간 단위 연결 해제 — 설치/레포/스냅샷은 건드리지 않는다(다른 공간 연결 유지). */
    fun disconnect() {
        if (status != GithubConnectionStatus.DISCONNECTED) {
            status = GithubConnectionStatus.DISCONNECTED
            touch()
        }
    }

    /** 끊었던 연결을 같은 공간으로 다시 켠다(connected_by 는 최초 연결자 그대로 둔다 — 감사 일관성). */
    fun reactivate() {
        if (status != GithubConnectionStatus.ACTIVE) {
            status = GithubConnectionStatus.ACTIVE
            touch()
        }
    }

    private fun touch() {
        updatedAt = LocalDateTime.now()
    }

    companion object {
        fun create(installationId: Long, space: GithubSpace, connectedByUserId: Long): GithubConnection {
            val now = LocalDateTime.now()
            return GithubConnection(
                connectionId = null,
                installationId = installationId,
                space = space,
                connectedByUserId = connectedByUserId,
                status = GithubConnectionStatus.ACTIVE,
                createdAt = now,
                updatedAt = now,
            )
        }

        fun reconstitute(
            connectionId: Long,
            installationId: Long,
            space: GithubSpace,
            connectedByUserId: Long,
            status: GithubConnectionStatus,
            createdAt: LocalDateTime,
            updatedAt: LocalDateTime,
        ) = GithubConnection(
            connectionId, installationId, space, connectedByUserId, status, createdAt, updatedAt,
        )
    }
}
