package org.studieojavry.publishapi.publishment.infrastructure.attachment

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

/**
 * 첨부 스토리지(core 와 동일 S3/MinIO 버킷) presigned URL 발급 설정.
 * publish-api 는 오브젝트를 쓰지 않고 **서명만** 하므로 S3Presigner 만 필요.
 * endpoint-public = 브라우저 도달 주소(공개 페이지의 `<img>` 가 접근).
 */
@Validated
@ConfigurationProperties(prefix = "publish.attachment.s3")
data class PublishAttachmentS3Properties(
    @field:NotBlank
    val bucket: String,
    val region: String = "us-east-1",
    /** presigned URL 호스트(브라우저 도달). null=AWS 기본. */
    val endpointPublic: String? = null,
    val pathStyle: Boolean = false,
    val accessKey: String? = null,
    val secretKey: String? = null,
    /** 공개 페이지 이미지 presigned URL TTL. */
    val ttl: Duration = Duration.ofMinutes(30),
)

@Configuration
class AttachmentPresignConfig {

    @Bean
    fun attachmentS3Presigner(props: PublishAttachmentS3Properties): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(props.region))
            .credentialsProvider(credentials(props))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle).build())
        props.endpointPublic?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    private fun credentials(props: PublishAttachmentS3Properties): AwsCredentialsProvider =
        if (!props.accessKey.isNullOrBlank() && !props.secretKey.isNullOrBlank()) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey))
        } else {
            DefaultCredentialsProvider.create()
        }
}
