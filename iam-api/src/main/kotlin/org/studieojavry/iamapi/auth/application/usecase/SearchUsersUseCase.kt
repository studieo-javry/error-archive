package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort

/**
 * displayName prefix 검색 — 멘션 자동완성용.
 *
 * - ACTIVE 사용자만 (PENDING_DELETION/SUSPENDED/DELETED 제외)
 * - viewer 본인은 결과에서 제외
 * - 빈 q → "최근 가입한 ACTIVE" 순 (default suggestion 후보 풀)
 * - 권한 가드는 *호출자* 책임 — 본 endpoint 는 *공개 사용자 디렉토리* 동작.
 *   (특정 콘텐츠에 멘션 가능한지는 core-api 가 케이스/워크스페이스 기준으로 별도 판단)
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
            .map { Item(userId = it.id!!, displayName = it.displayName, avatarUrl = it.avatarUrl) }
            .toList()
    }

    data class Item(val userId: Long, val displayName: String, val avatarUrl: String?)
}
