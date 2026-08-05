package org.studieojavry.coreapi.errorcase.attachment.infrastructure.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.attachment.application.port.AttachmentStoragePort
import org.studieojavry.coreapi.errorcase.attachment.config.AttachmentS3Properties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.time.Duration

/**
 * S3 호환 스토리지 어댑터 (AWS S3 / MinIO 공통). 내부 호출은 [s3], presigned URL 서명은 [presigner].
 * presigner 는 브라우저 도달 endpoint 로 구성돼 있어 서명 URL 호스트가 곧 브라우저가 접근할 주소.
 */
@Component
class S3AttachmentStorageAdapter(
    private val s3: S3Client,
    private val presigner: S3Presigner,
    private val props: AttachmentS3Properties,
) : AttachmentStoragePort {

    private val log = KotlinLogging.logger {}

    override fun put(objectKey: String, bytes: ByteArray, contentType: String) {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(objectKey)
                .contentType(contentType)   // inline 렌더에 필수
                .contentLength(bytes.size.toLong())
                .build(),
            RequestBody.fromBytes(bytes),
        )
        log.debug { "[attachment-s3] put key=$objectKey ($contentType, ${bytes.size}B)" }
    }

    override fun presignGet(
        objectKey: String,
        disposition: AttachmentStoragePort.Disposition,
        ttl: Duration,
    ): URI {
        val dispositionValue =
            if (disposition == AttachmentStoragePort.Disposition.ATTACHMENT) "attachment" else "inline"
        val getReq = GetObjectRequest.builder()
            .bucket(props.bucket)
            .key(objectKey)
            .responseContentDisposition(dispositionValue)
            .build()
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(getReq)
                .build()
        )
        return presigned.url().toURI()
    }

    override fun getBytes(objectKey: String): ByteArray =
        s3.getObjectAsBytes(
            GetObjectRequest.builder().bucket(props.bucket).key(objectKey).build()
        ).asByteArray()

    override fun delete(objectKey: String) {
        try {
            s3.deleteObject { it.bucket(props.bucket).key(objectKey) }
        } catch (e: NoSuchKeyException) {
            // 멱등 — 이미 없으면 무시
            log.debug { "[attachment-s3] delete no-op (key already absent) key=$objectKey" }
        }
    }
}
