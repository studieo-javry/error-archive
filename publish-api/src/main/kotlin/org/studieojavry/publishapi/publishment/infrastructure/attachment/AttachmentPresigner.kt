package org.studieojavry.publishapi.publishment.infrastructure.attachment

import org.springframework.stereotype.Service
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

/**
 * 첨부 objectKey → presigned inline URL. 공개 페이지(HTML/PDF/MD) 렌더 시점에 발급.
 * 발행 스냅샷은 objectKey(불변)만 저장하고 URL 은 매 렌더마다 새로 서명한다(만료 회피).
 */
@Service
class AttachmentPresigner(
    private val presigner: S3Presigner,
    private val props: PublishAttachmentS3Properties,
) {
    /** objectKey → presigned inline URL. objectKey 가 null 이면 null. (PDF 이미지 임베드용) */
    fun viewUrl(objectKey: String?): String? = signedUrl(objectKey, contentType = null, disposition = "inline")

    /**
     * 안정 파일 라우트가 발급하는 presigned URL.
     * responseContentType + responseContentDisposition 을 지정해 브라우저 렌더/다운로드를 제어.
     */
    fun signedUrl(objectKey: String?, contentType: String?, disposition: String): String? {
        if (objectKey.isNullOrBlank()) return null
        val getReq = GetObjectRequest.builder()
            .bucket(props.bucket)
            .key(objectKey)
            .responseContentDisposition(disposition)
            .apply { if (!contentType.isNullOrBlank()) responseContentType(contentType) }
            .build()
        return presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(props.ttl)
                .getObjectRequest(getReq)
                .build()
        ).url().toString()
    }
}
