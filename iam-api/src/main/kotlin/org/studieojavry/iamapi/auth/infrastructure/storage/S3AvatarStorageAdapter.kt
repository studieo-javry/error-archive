package org.studieojavry.iamapi.auth.infrastructure.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.studieojavry.iamapi.auth.application.port.AvatarStoragePort
import org.studieojavry.iamapi.auth.config.AvatarS3Properties
import org.studieojavry.iamapi.auth.config.AvatarStorageProperties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

/**
 * S3 호환 아바타 스토리지 (비-local: dev=MinIO, prod=OCI Object Storage).
 *
 * - objectKey: `avatars/<userId>/<uuid>.<ext>`
 * - 공개 URL: `<publicBaseUrl>/<objectKey>` — DB(user.avatarUrl)에 영구 저장되므로 안정적 URL.
 *   버킷은 public-read(또는 CDN 전면) 여야 브라우저가 직접 로드 가능.
 * - 덮어쓰기 X: 재업로드 시 새 uuid. 이전 파일 정리는 호출자(UseCase)가 delete 로.
 */
@Component
@Profile("!local")
class S3AvatarStorageAdapter(
    private val avatarS3Client: S3Client,
    private val s3Props: AvatarS3Properties,
    private val avatarProps: AvatarStorageProperties,
) : AvatarStoragePort {

    private val log = KotlinLogging.logger {}
    private val publicBase = avatarProps.publicBaseUrl.trimEnd('/')

    override fun store(
        userId: Long,
        originalFileName: String?,
        contentType: String,
        bytes: ByteArray,
    ): AvatarStoragePort.StoredAvatar {
        val objectKey = "avatars/$userId/${UUID.randomUUID()}.${ext(contentType, originalFileName)}"
        avatarS3Client.putObject(
            PutObjectRequest.builder()
                .bucket(s3Props.bucket)
                .key(objectKey)
                .contentType(contentType)          // 브라우저 inline 렌더에 필수
                .contentLength(bytes.size.toLong())
                .build(),
            RequestBody.fromBytes(bytes),
        )
        val publicUrl = "$publicBase/$objectKey"
        log.debug { "[avatar-s3] put key=$objectKey ($contentType, ${bytes.size}B) -> $publicUrl" }
        return AvatarStoragePort.StoredAvatar(publicUrl = publicUrl)
    }

    /** 우리 publicBaseUrl 로 시작하는 URL 만 삭제. 그 외(외부 URL)면 무시. 멱등. */
    override fun delete(publicUrl: String) {
        val prefix = "$publicBase/"
        if (!publicUrl.startsWith(prefix)) return
        val objectKey = publicUrl.removePrefix(prefix)
        // S3 deleteObject 는 키가 없어도 예외 없이 no-op → 멱등.
        avatarS3Client.deleteObject { it.bucket(s3Props.bucket).key(objectKey) }
        log.debug { "[avatar-s3] delete key=$objectKey" }
    }

    private fun ext(contentType: String, originalFileName: String?): String = when (contentType.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> originalFileName?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() && it.length <= 5 } ?: "img"
    }
}
