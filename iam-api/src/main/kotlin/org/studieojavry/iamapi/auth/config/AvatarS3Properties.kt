package org.studieojavry.iamapi.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 아바타 S3 호환 스토리지 설정 (비-local 프로파일). dev/stg=MinIO, prod=OCI Object Storage(S3 호환).
 *
 * 아바타 URL 은 `user.avatarUrl` 에 **영구 저장**되므로 만료되는 presigned URL 이 아니라
 * **안정적 공개 URL**([AvatarStorageProperties.publicBaseUrl] + objectKey)을 사용한다.
 * → 버킷은 **public-read** 로 설정하거나 앞에 CDN 을 둬야 브라우저가 아바타를 직접 로드할 수 있다.
 */
@ConfigurationProperties(prefix = "iam.avatar.s3")
data class AvatarS3Properties(
    val bucket: String = "error-archive-avatars",
    val region: String = "us-east-1",
    /** 앱→스토리지 내부 endpoint. null 이면 SDK 기본(AWS) endpoint. MinIO/OCI 는 지정. */
    val endpoint: String? = null,
    val pathStyle: Boolean = true,
    val accessKey: String? = null,
    val secretKey: String? = null,
)
