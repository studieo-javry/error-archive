package org.studieojavry.publishapi.demo.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 데모(둘러보기) 발행물 시드 설정.
 *
 *  - `enabled` = false 면 모든 데모 시드/정리 진입점이 no-op (실서비스 기본).
 *  - 워크스페이스 데모 발행물은 부팅 시 정적·멱등 시드.
 *  - 게스트 개인 발행물은 `/me` 지연 시드 → `purge-cron` 으로 야간 수거.
 *
 * core-api 데모 리셋(04:10 KST) 이후에 purge 가 돌도록 기본 04:30 으로 둔다.
 */
@ConfigurationProperties(prefix = "demo")
data class DemoPublishmentProperties(
    val enabled: Boolean = false,
    /** 게스트 데모 발행물 야간 정리 cron (Asia/Seoul). core 리셋(04:10) 이후. */
    val purgeCron: String = "0 30 4 * * *",
)
