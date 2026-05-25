package org.studieojavry.iamapi.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 아바타 이미지 로컬 파일시스템 저장 설정.
 *
 * - storagePath: 디스크 절대/상대 경로. 기동 시 디렉토리 자동 생성.
 * - publicBaseUrl: 응답에 박을 base URL. 정적 핸들러가 이 prefix 로 매핑된다.
 *   기본값은 게이트웨이를 거치는 경로(`http://localhost:8000/avatars`) — gateway 라우팅과 정합.
 * - maxFileSizeBytes: 업로드 허용 최대 크기. 5MB 기본.
 * - allowedContentTypes: 허용 MIME prefix. 이미지만.
 */
@ConfigurationProperties(prefix = "iam.avatar")
data class AvatarStorageProperties(
    val storagePath: String = "./var/avatars",
    val publicBaseUrl: String = "http://localhost:8000/avatars",
    val maxFileSizeBytes: Long = 5 * 1024 * 1024,
    val allowedContentTypes: List<String> = listOf(
        "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif"
    ),
)
