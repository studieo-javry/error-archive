package org.studieojavry.coreapi.errorcase.attachment.infrastructure.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.config.AttachmentStorageProperties
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 로컬 파일 시스템 기반 임시 어댑터. 운영에서는 S3/GCS 어댑터로 교체.
 *
 * 디스크 layout: <storagePath>/<markerId 앞 2자>/<markerId>__<safe-fileName>
 * 공개 URL    : <publicBaseUrl>/<markerId>  (저장소 내부 구조를 노출하지 않음)
 */
@Component
class LocalFileSystemAttachmentStorageAdapter(
    private val properties: AttachmentStorageProperties
) : AttachmentStoragePort {

    private val log = KotlinLogging.logger {}
    private val safeNameRegex = Regex("""[^A-Za-z0-9._-]""")

    override fun store(
        markerId: String,
        fileName: String,
        bytes: ByteArray,
        contentType: String
    ): AttachmentStoragePort.StoredAttachment {
        val target = resolvePath(markerId, fileName)
        Files.createDirectories(target.parent)
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        val publicUrl = "${properties.publicBaseUrl.trimEnd('/')}/$markerId"
        log.debug { "[attachment-storage] saved $target ($contentType, ${bytes.size}B) -> $publicUrl" }

        return AttachmentStoragePort.StoredAttachment(storageUrl = publicUrl)
    }

    override fun load(markerId: String, fileName: String): ByteArray {
        val target = resolvePath(markerId, fileName)
        return try {
            Files.readAllBytes(target)
        } catch (e: NoSuchFileException) {
            throw AttachmentFileNotFoundException(markerId, target.toString(), e)
        }
    }

    /** 멱등 삭제 — 파일이 이미 없으면 deleteIfExists 가 false 만 반환(예외 없음). */
    override fun delete(markerId: String, fileName: String) {
        val target = resolvePath(markerId, fileName)
        val deleted = Files.deleteIfExists(target)
        log.debug { "[attachment-storage] delete $target deleted=$deleted" }
    }

    private fun resolvePath(markerId: String, fileName: String): Path {
        val shard = markerId.take(2).ifBlank { "00" }
        val safeName = "${markerId}__${fileName.replace(safeNameRegex, "_")}"
        return Path.of(properties.storagePath, shard, safeName)
    }
}

class AttachmentFileNotFoundException(
    val markerId: String,
    path: String,
    cause: Throwable? = null
) : RuntimeException("attachment file not found for markerId=$markerId at $path", cause)
