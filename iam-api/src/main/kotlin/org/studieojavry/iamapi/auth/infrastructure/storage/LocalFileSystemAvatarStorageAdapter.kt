package org.studieojavry.iamapi.auth.infrastructure.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.application.port.AvatarStoragePort
import org.studieojavry.iamapi.auth.config.AvatarStorageProperties
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * 로컬 디스크 기반 아바타 저장. 운영에서는 S3/GCS adapter 로 교체.
 *
 * 디스크 layout: `<storagePath>/<userId>/<uuid>.<ext>` — userId 별 디렉토리로 분리
 * 공개 URL    : `<publicBaseUrl>/<userId>/<uuid>.<ext>`
 *
 * 같은 user 가 여러 번 업로드해도 *새 파일* 로 저장 (uuid). 이전 파일 정리는 use case 가
 * 기존 avatarUrl 을 보존했다가 `delete(oldUrl)` 호출.
 */
@Component
@Profile("local")   // 비-local 은 S3AvatarStorageAdapter (dev=MinIO / prod=OCI)
class LocalFileSystemAvatarStorageAdapter(
    private val properties: AvatarStorageProperties,
) : AvatarStoragePort {

    private val log = KotlinLogging.logger {}

    override fun store(
        userId: Long,
        originalFileName: String?,
        contentType: String,
        bytes: ByteArray,
    ): AvatarStoragePort.StoredAvatar {
        val ext = inferExtension(originalFileName, contentType)
        val fileName = "${UUID.randomUUID()}.$ext"
        val target = resolvePath(userId, fileName)
        Files.createDirectories(target.parent)
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        val publicUrl = "${properties.publicBaseUrl.trimEnd('/')}/$userId/$fileName"
        log.debug { "[avatar-storage] saved $target ($contentType, ${bytes.size}B) -> $publicUrl" }

        return AvatarStoragePort.StoredAvatar(publicUrl = publicUrl)
    }

    /**
     * 멱등 삭제. publicUrl 의 우리 prefix 가 맞을 때만 디스크 file 매핑.
     * 외부 URL(GitHub avatar 등) 이면 조용히 무시.
     */
    override fun delete(publicUrl: String) {
        val prefix = properties.publicBaseUrl.trimEnd('/') + "/"
        if (!publicUrl.startsWith(prefix)) {
            log.debug { "[avatar-storage] skip delete (external url): $publicUrl" }
            return
        }
        val relative = publicUrl.removePrefix(prefix)
        val parts = relative.split('/', limit = 2)
        if (parts.size != 2) return
        val (userIdStr, fileName) = parts
        val userId = userIdStr.toLongOrNull() ?: return
        val target = resolvePath(userId, fileName)
        val deleted = Files.deleteIfExists(target)
        log.debug { "[avatar-storage] delete $target deleted=$deleted" }
    }

    private fun resolvePath(userId: Long, fileName: String): Path =
        Path.of(properties.storagePath, userId.toString(), fileName)

    private fun inferExtension(originalFileName: String?, contentType: String): String {
        // 1) originalFilename suffix 우선 (사용자 의도 보존)
        originalFileName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?.let { return it.lowercase() }
        // 2) contentType 매핑
        return when (contentType.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "bin"
        }
    }
}