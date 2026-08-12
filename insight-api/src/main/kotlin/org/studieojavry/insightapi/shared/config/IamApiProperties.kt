package org.studieojavry.insightapi.shared.config

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * insight → iam-api(사용자 timezone 조회) base-url.
 *
 * fail-fast: 절대 http(s) URL 이어야 한다. prod 에서 `${INSIGHT_IAM_API_BASE_URL}` 미설정 시
 * Binder 는 **미해소 placeholder 를 리터럴 문자열로 바인딩**하므로(@NotBlank 로는 못 잡음),
 * URL 패턴 검증으로 리터럴("${...}")을 걸러 부팅을 중단시킨다. local=application-local.yml,
 * test=application-test.yml 이 concrete 값을 제공.
 */
@Validated
@ConfigurationProperties(prefix = "insight.iam-api")
data class IamApiProperties(
    @field:Pattern(
        regexp = "^https?://.+",
        message = "insight.iam-api.base-url 은 절대 http(s) URL 이어야 함 — prod 는 INSIGHT_IAM_API_BASE_URL 필수",
    )
    val baseUrl: String,
)
