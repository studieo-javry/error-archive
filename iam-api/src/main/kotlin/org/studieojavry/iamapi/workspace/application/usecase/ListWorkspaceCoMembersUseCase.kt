package org.studieojavry.iamapi.workspace.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.workspace.application.port.WorkspaceMemberRepositoryPort

/**
 * **Internal-only** — userId 가 속한 모든 워크스페이스의 *다른 멤버* userId 집합.
 *
 * core-api 의 *suggested-followees* 추천 신호 — "같은 워크스페이스 멤버" 후보 가져오기 위함.
 * 자기 자신은 제외, distinct.
 */
@Service
class ListWorkspaceCoMembersUseCase(
    private val memberRepository: WorkspaceMemberRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long, size: Int): List<Long> {
        val bounded = size.coerceIn(1, MAX_SIZE)
        // 1) 내가 속한 workspace 들
        val myWorkspaces = memberRepository.findByUserId(userId).map { it.workspaceId }
        if (myWorkspaces.isEmpty()) return emptyList()
        // 2) 각 workspace 의 멤버 union, 자기 제외, distinct
        return myWorkspaces.asSequence()
            .flatMap { wsId -> memberRepository.findByWorkspaceId(wsId).asSequence() }
            .map { it.userId }
            .filter { it != userId }
            .distinct()
            .take(bounded)
            .toList()
    }

    companion object {
        private const val MAX_SIZE = 500
    }
}
