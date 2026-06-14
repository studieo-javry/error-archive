package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort

@Service
class GetMyProfileUseCase(
    private val userRepository: UserRepositoryPort
) {

    @Transactional(readOnly = true)
    fun invoke(userId: Long): Result {
        val user = userRepository.findById(userId)
            ?: throw NoSuchElementException("user not found: $userId")
        return Result(
            userId = user.id!!,
            email = user.email?.value,
            handle = user.handle,
            displayName = user.displayName,
            avatarUrl = user.avatarUrl,
            bio = user.bio,
            status = user.status.name,
            pendingDeletionAt = user.pendingDeletionAt,
            createdAt = user.createdAt,
            language = user.language,
            timezone = user.timezone,
            theme = user.theme,
            defaultWorkspaceId = user.defaultWorkspaceId,
        )
    }

    data class Result(
        val userId: Long,
        val email: String?,
        val handle: String,
        val displayName: String,
        val avatarUrl: String?,
        val bio: String?,
        val status: String,
        /** PENDING_DELETION 일 때 grace 시작 시각. 클라가 만료까지 남은 시간 표시 가능. */
        val pendingDeletionAt: java.time.Instant?,
        /** 계정 가입 시각 (불변). */
        val createdAt: java.time.Instant,
        val language: String?,
        val timezone: String?,
        val theme: org.studieojavry.iamapi.auth.domain.model.vo.Theme,
        val defaultWorkspaceId: Long?,
    )
}
