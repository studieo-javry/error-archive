package org.studieojavry.publishapi.shared.config

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 공개 페이지 base URL — 응답/공유 링크/sitemap 의 절대 URL 생성에 사용.
 *
 * fail-fast: 미해소 `${PUBLISH_PAGE_PUBLIC_BASE_URL}` 는 http(s) URL 패턴에 안 맞아 부팅이 막힌다.
 *   기본값 없이 local/test yml 에 concrete 값. prod 에서 env 미설정 시 localhost 링크가 나가는 것 방지.
 */
@Validated
@ConfigurationProperties(prefix = "publish.page")
data class PagePublicProperties(
    @field:Pattern(regexp = "^https?://.+", message = "publish.page.public-base-url 은 절대 http(s) URL — PUBLISH_PAGE_PUBLIC_BASE_URL env 필수")
    val publicBaseUrl: String,
)
