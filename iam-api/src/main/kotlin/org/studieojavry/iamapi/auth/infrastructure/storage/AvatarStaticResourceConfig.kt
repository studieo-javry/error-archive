package org.studieojavry.iamapi.auth.infrastructure.storage

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.studieojavry.iamapi.auth.config.AvatarStorageProperties
import java.io.File

/**
 * 아바타 storage 디렉토리를 정적 URL "/avatars/..." 패턴으로 노출.
 * gateway 가 같은 prefix 를 iam-api 로 라우팅하므로, 외부 클라이언트는
 * http://localhost:8000/avatars/{userId}/{file} 로 접근한다.
 */
@Configuration
@Profile("local")   // local 디스크 저장분만 정적 서빙. 비-local 은 S3/OCI 공개 URL 로 직접 서빙.
class AvatarStaticResourceConfig(
    private val properties: AvatarStorageProperties,
) : WebMvcConfigurer {

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        // 절대경로로 정규화 + 끝에 / 보장 (Spring 의 file: 핸들러 요구사항)
        val absRoot = File(properties.storagePath).absoluteFile
        absRoot.mkdirs()
        val location = "file:${absRoot.absolutePath}/"
        registry.addResourceHandler("/avatars/**")
            .addResourceLocations(location)
            .setCachePeriod(300) // 5분 — 같은 URL 은 *불변 파일* (uuid) 이라 캐시해도 안전
    }
}
