package org.studieojavry.iamapi.demo.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * "로그인 없이 둘러보기"(게스트 데모) 설정.
 *
 * - [enabled] = false 면 게스트 로그인/부트스트랩/리셋 모두 비활성(기본). prod 노출 방지.
 *   stg 는 overlay 의 `DEMO_ENABLED=true` 로 켠다(stg 는 prod Spring profile 로 구동).
 * - 데모 봇([botHandle])이 시드 콘텐츠의 **고정 작성자** — 야간 리셋이 게스트만 지우고 봇/시드는 보존.
 */
@Validated
@ConfigurationProperties(prefix = "demo")
data class DemoProperties(
    val enabled: Boolean = false,

    /** 데모 워크스페이스 slug(kebab-case). 모든 게스트가 이 워크스페이스 하나를 공유. */
    @field:NotBlank
    val workspaceSlug: String = "demo",

    /** 데모 워크스페이스 표시 이름(ASCII/숫자/공백/하이픈). */
    @field:NotBlank
    val workspaceName: String = "Demo Workspace",

    /** 시드 콘텐츠 작성자 = 데모 봇. 게스트가 아니라 리셋에서 보존됨. */
    @field:NotBlank
    val botHandle: String = "demo-bot",

    @field:NotBlank
    val botDisplayName: String = "Demo Bot",

    /** 게스트 토큰 TTL(초). refresh 없음 — 만료 시 버튼 재클릭. */
    @field:Min(300)
    val guestTokenTtlSeconds: Long = 3600,

    /** 게스트 정리 cron(Asia/Seoul). 기본 매일 04:00. */
    @field:NotBlank
    val resetCron: String = "0 0 4 * * *",

    /** 한 번의 리셋에서 정리할 게스트 최대 수(폭주 방어). */
    @field:Min(1)
    val maxGuestsPerReset: Int = 5000,
)
