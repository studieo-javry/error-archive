package org.studieojavry.coreapi.errorcase.attachment.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * S3 호환 오브젝트 스토리지 설정. prod=AWS S3, stg·local=MinIO — 구현은 동일(S3Client),
 * endpoint/credentials/bucket 만 프로파일별로 다르다.
 *
 * **endpoint 이원화**: 앱→스토리지 내부 호출용([endpointInternal]) 과
 * 브라우저→스토리지 presigned URL 호스트([endpointPublic]) 를 분리한다.
 * docker 안에서 앱이 돌면 internal 은 `http://minio:9000`, public 은 브라우저가 닿는 `http://localhost:9000`.
 * AWS prod 에선 둘 다 null → SDK 기본 리전 엔드포인트.
 */
@Validated
@ConfigurationProperties(prefix = "core.attachment.s3")
data class AttachmentS3Properties(
    @field:NotBlank
    val bucket: String,

    val region: String = "us-east-1",

    /** 앱→스토리지 (put/get/delete). null=AWS 기본 엔드포인트. */
    val endpointInternal: String? = null,

    /** presigned URL 호스트(브라우저 도달). null=AWS 기본(=internal 과 동일). */
    val endpointPublic: String? = null,

    /** MinIO=true (path-style addressing). AWS=false (virtual-host). */
    val pathStyle: Boolean = false,

    /** null 이면 SDK 기본 credentials chain(IAM Role 등) 사용. */
    val accessKey: String? = null,
    val secretKey: String? = null,
)
