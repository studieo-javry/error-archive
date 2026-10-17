package org.studieojavry.coreapi.demo.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * core-api 쪽 게스트 데모 설정. 데모 워크스페이스/봇 ID 는 iam-api 가 소유하므로
 * core 는 `enabled` 와 리셋 cron 만 안다(ID 는 iam `/internal/demo/identity` 로 조회).
 *
 * `@ConfigurationPropertiesScan`(CoreApiConfigRegistration) 으로 자동 등록.
 */
@Validated
@ConfigurationProperties(prefix = "demo")
data class DemoProperties(
    val enabled: Boolean = false,

    /**
     * 데모 콘텐츠 wipe+reseed cron(Asia/Seoul). iam 의 게스트 사용자 정리(04:00) 뒤에 돌도록 기본 04:10.
     */
    @field:NotBlank
    val resetCron: String = "0 10 4 * * *",
)
