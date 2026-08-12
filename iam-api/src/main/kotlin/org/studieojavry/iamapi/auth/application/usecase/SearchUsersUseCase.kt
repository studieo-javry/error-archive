package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort

/**
 * 사용자 검색 — 멘션 자동완성 + 사용자 디렉토리.
 *
 * 매칭: `handle` 또는 `displayName` 의 prefix (대소문자 무시).
 * adapter 가 handle 우선 + display 보조로 합쳐 반환.
 * - ACTIVE 사용자만 (PENDING_DELETION/SUSPENDED/DELETED 제외)
 * - viewer 본인은 결과에서 제외
 * - 빈 q → "최근 가입한 ACTIVE" 순 (default suggestion 후보 풀)
 */
@Service
class SearchUsersUseCase(
    private val userRepository: UserRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(rawQuery: String?, limit: Int, viewerUserId: Long?): List<Item> {
        val q = rawQuery?.trim().orEmpty().take(100)
        val cap = limit.coerceIn(1, 20)
        return userRepository.searchByDisplayNamePrefix(q, cap + 1)
            .asSequence()
            .filter { it.id != viewerUserId }
            .take(cap)
            .map {
                Item(
                    userId = it.id!!,
                    handle = it.handle,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl,
                )
            }
            .toList()
    }

    data class Item(val userId: Long, val handle: String, val displayName: String, val avatarUrl: String?)
}
