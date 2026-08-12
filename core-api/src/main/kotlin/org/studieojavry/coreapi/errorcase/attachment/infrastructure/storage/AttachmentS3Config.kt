package org.studieojavry.coreapi.errorcase.attachment.infrastructure.storage

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.studieojavry.coreapi.errorcase.attachment.config.AttachmentS3Properties
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

/**
 * S3Client(내부 호출) 와 S3Presigner(서명) 빈. **endpoint 를 분리**하는 것이 핵심 —
 * presigner 는 브라우저가 닿는 [AttachmentS3Properties.endpointPublic] 로 서명해야
 * 서명 URL 안의 호스트가 `minio:9000`(docker 내부) 이 아니라 `localhost:9000`(브라우저) 이 된다.
 *
 * accessKey/secretKey 가 없으면(prod IAM Role 등) SDK 기본 credentials chain 을 사용한다.
 */
@Configuration
class AttachmentS3Config {

    @Bean
    fun s3Client(props: AttachmentS3Properties): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(props.region))
            .credentialsProvider(credentials(props))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle).build())
        props.endpointInternal?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    @Bean
    fun s3Presigner(props: AttachmentS3Properties): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(props.region))
            .credentialsProvider(credentials(props))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle).build())
        // 서명 URL 호스트 = 브라우저 도달 주소. null(AWS) 이면 기본 리전 엔드포인트.
        (props.endpointPublic ?: props.endpointInternal)?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    private fun credentials(props: AttachmentS3Properties): AwsCredentialsProvider =
        if (!props.accessKey.isNullOrBlank() && !props.secretKey.isNullOrBlank()) {
            StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey))
        } else {
            DefaultCredentialsProvider.create() // prod: IAM Role / env / profile chain
        }
}
