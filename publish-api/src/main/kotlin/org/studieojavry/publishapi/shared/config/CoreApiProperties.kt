package org.studieojavry.publishapi.shared.config

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * publish-api → core-api 호출 base-url (발행 시 `/internal/error-cases/{id}/full-data` 조회).
 *
 * fail-fast: 미해소 `${PUBLISH_CORE_API_BASE_URL}` 는 http(s) URL 패턴에 안 맞아 부팅이 막힌다.
 *   기본값을 두지 않고(그리고 base application.yml 에도 두지 않고) local/test yml 에 concrete 값을 둔다.
 *   — 기본값이 있으면 prod 에서 env 미설정 시 localhost 로 조용히 폴백해 발행이 전부 실패한다.
 */
@Validated
@ConfigurationProperties(prefix = "publish.core-api")
data class CoreApiProperties(
    @field:Pattern(regexp = "^https?://.+", message = "publish.core-api.base-url 은 절대 http(s) URL — PUBLISH_CORE_API_BASE_URL env 필수")
    val baseUrl: String,
)
