package org.studieojavry.iamapi.auth.application.usecase

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.iamapi.auth.application.port.AvatarStoragePort
import org.studieojavry.iamapi.auth.application.port.UserRepositoryPort
import org.studieojavry.iamapi.auth.config.AvatarStorageProperties

/**
 * 아바타 업로드 / 제거.
 *
 * - upload: 파일 검증(MIME · size) → storage 저장 → `user.changeAvatar(newUrl)`
 *           기존 avatarUrl 이 *우리 storage URL* 이면 *비동기 정리 없이* 즉시 delete 호출(멱등).
 *           외부 URL(GitHub avatar 등) 이면 그냥 무시 — storage adapter 가 prefix 검사 후 skip.
 * - remove: `user.changeAvatar(null)` + 기존 파일이 우리 storage 면 delete.
 *
 * 동시성: 같은 user 가 빠르게 두 번 업로드해도 *각 호출이 독립적 파일* 을 만든다. DB 의 avatarUrl
 *        UPDATE 가 직렬화되므로 마지막 호출의 URL 이 최종. 이전 호출의 파일은 *바로 delete* 되거나
 *        (덮어쓴 호출이 처리하면) 다음 업로드/remove 때 정리. 잔여 orphan 은 운영상 무시 가능.
 */
@Service
class UpdateMyAvatarUseCase(
    private val userRepository: UserRepositoryPort,
    private val avatarStorage: AvatarStoragePort,
    private val properties: AvatarStorageProperties,
) {
    @Transactional
    fun upload(
        userId: Long,
        originalFileName: String?,
        contentType: String?,
        bytes: ByteArray,
    ): Result {
        validate(contentType, bytes)
        val resolvedType = contentType!!.lowercase()

        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(userId)
        val oldUrl = user.avatarUrl

        val stored = avatarStorage.store(userId, originalFileName, resolvedType, bytes)
        user.changeAvatar(stored.publicUrl)
        userRepository.save(user)

        // 이전 파일 정리 (우리 storage 인 경우에만 adapter 가 실제 삭제)
        oldUrl?.let { avatarStorage.delete(it) }

        return Result(avatarUrl = stored.publicUrl)
    }

    @Transactional
    fun remove(userId: Long): Result {
        val user = userRepository.findById(userId)
            ?: throw UserNotFoundException(userId)
        val oldUrl = user.avatarUrl
        user.changeAvatar(null)
        userRepository.save(user)
        oldUrl?.let { avatarStorage.delete(it) }
        return Result(avatarUrl = null)
    }

    private fun validate(contentType: String?, bytes: ByteArray) {
        if (bytes.isEmpty()) throw AvatarUploadInvalidException("empty file")
        if (bytes.size.toLong() > properties.maxFileSizeBytes) {
            throw AvatarUploadInvalidException(
                "file too large: ${bytes.size}B > ${properties.maxFileSizeBytes}B"
            )
        }
        val ct = contentType?.lowercase()?.substringBefore(';')?.trim()
            ?: throw AvatarUploadInvalidException("missing Content-Type")
        if (ct !in properties.allowedContentTypes) {
            throw AvatarUploadInvalidException(
                "unsupported content type: $ct (allowed: ${properties.allowedContentTypes.joinToString(", ")})"
            )
        }
    }

    data class Result(val avatarUrl: String?)
}

/** 업로드 검증 실패 — 400 매핑. */
class AvatarUploadInvalidException(message: String) : RuntimeException(message)
