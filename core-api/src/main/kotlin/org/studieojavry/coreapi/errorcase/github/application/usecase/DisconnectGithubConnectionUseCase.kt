package org.studieojavry.coreapi.errorcase.github.application.usecase

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.github.application.port.GithubConnectionRepositoryPort
import org.studieojavry.coreapi.errorcase.github.config.ConditionalOnGithubEnabled
import org.studieojavry.coreapi.errorcase.shared.application.port.WorkspaceQueryPort

/**
 * 공간별 연결 해제 — 그 공간에서만 연결을 끊는다(disconnect). **설치·레포·스냅샷은 그대로** 두고
 * 다른 공간의 ACTIVE 연결에도 영향이 없다. GitHub App 설치 자체를 지우는 게 아니다.
 *
 * 멱등: 이미 끊긴 연결에 다시 호출해도 no-op. 권한은 연결의 공간 기준([requireSpaceAccess]).
 * 끊은 뒤 재연결하면 같은 연결 행을 reactivate 한다(감사: 최초 연결자 유지).
 */
@Component
@ConditionalOnGithubEnabled
class DisconnectGithubConnectionUseCase(
    private val connections: GithubConnectionRepositoryPort,
    private val workspaceQuery: WorkspaceQueryPort,
) {
    private val log = KotlinLogging.logger {}

    fun invoke(userId: Long, connectionId: Long) {
        val connection = connections.findByConnectionId(connectionId)
            ?: throw GithubConnectionNotFoundException("연결을 찾을 수 없습니다: $connectionId")
        requireSpaceAccess(workspaceQuery, userId, connection.space)
        if (connection.isActive) {
            connection.disconnect()
            connections.save(connection)
            log.info { "github connection disconnected: connection=$connectionId space=${connection.space} by=$userId" }
        }
    }
}
