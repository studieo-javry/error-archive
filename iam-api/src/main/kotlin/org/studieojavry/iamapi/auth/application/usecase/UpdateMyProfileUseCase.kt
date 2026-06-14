package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.command.UpdateMyProfileCommand
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.domain.model.vo.Theme
import java.time.Instant

@Service
class UpdateMyProfileUseCase(
    private val userRepository: UserRepositoryPort
) {

    @Transactional
    fun invoke(command: UpdateMyProfileCommand): Result {
        val user = userRepository.findById(command.userId)
            ?: throw NoSuchElementException("user not found: ${command.userId}")

        command.displayName?.let { user.changeDisplayName(it.trim()) }

        // avatarUrl 은 *set 만*. 비우기는 DELETE /users/me/avatar (storage 정리 포함).
        command.avatarUrl?.takeIf { it.isNotBlank() }?.let { user.changeAvatar(it.trim()) }

        when {
            command.clearBio -> user.changeBio(null)
            command.bio != null -> user.changeBio(command.bio)
        }

        when {
            command.clearLanguage -> user.changeLanguage(null)
            command.language != null -> user.changeLanguage(command.language)
        }

        when {
            command.clearTimezone -> user.changeTimezone(null)
            command.timezone != null -> user.changeTimezone(command.timezone)
        }

        command.theme?.let { user.changeTheme(it) }

        when {
            command.clearDefaultWorkspace -> user.changeDefaultWorkspace(null)
            command.defaultWorkspaceId != null -> user.changeDefaultWorkspace(command.defaultWorkspaceId)
        }

        val saved = userRepository.save(user)
        return Result(
            userId = saved.id!!,
            email = saved.email?.value,
            handle = saved.handle,
            displayName = saved.displayName,
            avatarUrl = saved.avatarUrl,
            bio = saved.bio,
            status = saved.status.name,
            pendingDeletionAt = saved.pendingDeletionAt,
            createdAt = saved.createdAt,
            language = saved.language,
            timezone = saved.timezone,
            theme = saved.theme,
            defaultWorkspaceId = saved.defaultWorkspaceId,
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
        val pendingDeletionAt: Instant?,
        val createdAt: Instant,
        val language: String?,
        val timezone: String?,
        val theme: Theme,
        val defaultWorkspaceId: Long?,
    )
}
