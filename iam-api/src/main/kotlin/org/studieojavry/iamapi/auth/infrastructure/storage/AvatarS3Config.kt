package org.studieojavry.iamapi.auth.infrastructure.storage

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.studieojavry.iamapi.auth.config.AvatarS3Properties
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI

/**
 * 아바타용 S3Client (비-local 프로파일만). 아바타는 공개 URL 로 서빙하므로 presigner 는 불필요.
 * accessKey/secretKey 가 없으면 SDK 기본 credentials chain(예: prod IAM Role) 사용.
 */
@Configuration
@Profile("!local")
class AvatarS3Config {

    @Bean
    fun avatarS3Client(props: AvatarS3Properties): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(props.region))
            .credentialsProvider(credentials(props))
            // OCI Object Storage(S3 호환)는 aws-chunked payload 서명을 미지원 → putObject 가
            // 403 "The required information to complete authentication was not provided" 로 실패.
            // chunkedEncodingEnabled(false) 로 단일 서명 payload 전송(MinIO 등도 호환).
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(props.pathStyle)
                    .chunkedEncodingEnabled(false)
                    .build(),
            )
        props.endpoint?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    private fun credentials(props: AvatarS3Properties): AwsCredentialsProvider =
        if (!props.accessKey.isNullOrBlank() && !props.secretKey.isNullOrBlank()) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey))
        } else {
            DefaultCredentialsProvider.create()
        }
}
