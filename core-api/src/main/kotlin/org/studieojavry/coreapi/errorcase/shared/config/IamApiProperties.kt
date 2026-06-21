package org.studieojavry.coreapi.errorcase.shared.config

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "core.iam-api")
data class IamApiProperties(
    // fail-fast: 절대 http(s) URL. prod 에서 ${CORE_IAM_API_BASE_URL} 미설정 시 Binder 가 미해소
    // placeholder 를 *리터럴 문자열*로 바인딩하므로(@NotBlank 로는 못 잡음) URL 패턴으로 걸러 부팅 중단.
    @field:Pattern(
        regexp = "^https?://.+",
        message = "core.iam-api.base-url 은 절대 http(s) URL 이어야 함 — prod 는 CORE_IAM_API_BASE_URL 필수",
    )
    val baseUrl: String
)