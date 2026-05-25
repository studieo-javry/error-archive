package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.User
import org.studieojavry.iamapi.auth.domain.model.vo.UserStatus

/**
 * 공개 프로필 조회 — 누구나(인증 사용자 포함) 볼 수 있는 정보만 반환.
 * 비공개 필드(email/language/timezone/theme/defaultWorkspaceId/pendingDeletionAt)는 노출 안 함.
 * status=DELETED 면 displayName 은 이미 익명화돼 있고, 다른 표시 정보는 null.
 */
@Service
class GetPublicProfileUseCase(
    private val userRepository: UserRepositoryPort,
) {
    @Transactional(readOnly = true)
    fun invoke(userId: Long): Result {
        val user = userRepository.findById(userId)
            ?: throw NoSuchElementException("user not found: $userId")
        return Result(
            userId = user.id!!,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            bio = user.bio,
            status = user.status.name,
            isDeleted = user.status == UserStatus.DELETED,
        )
    }

    data class Result(
        val userId: Long,
        val displayName: String,
        val avatarUrl: String?,
        val bio: String?,
        val status: String,
        val isDeleted: Boolean,
    )
}
