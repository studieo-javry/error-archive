package org.studieojavry.iamapi.demo.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * 게스트 데모 설정 등록. iam-api 는 `@ConfigurationPropertiesScan` 이 없어 명시 등록이 필요하다.
 * (`@EnableScheduling` 은 이미 전역 활성 — `SchedulingConfig`/`AccountDeletionConfig`.)
 */
@Configuration
@EnableConfigurationProperties(DemoProperties::class)
class DemoConfig
