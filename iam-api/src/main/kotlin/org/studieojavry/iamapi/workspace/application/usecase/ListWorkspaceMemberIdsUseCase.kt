package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort

/**
 * **Internal-only** — 워크스페이스의 *member userId list* batch 조회.
 *
 * core-api 등 service-to-service 호출자가 *알림 fan-out 대상자* 식별용으로 사용.
 * 권한 검증 없음 — internal JWT 통과한 호출만 도달.
 */
@Service
class ListWorkspaceMemberIdsUseCase(
    private val memberRepository: WorkspaceMemberRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(workspaceId: Long): List<Long> =
        memberRepository.findByWorkspaceId(workspaceId).map { it.userId }
}
