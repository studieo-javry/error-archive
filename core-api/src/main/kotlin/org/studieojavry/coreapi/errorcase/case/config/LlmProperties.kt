package org.studieojavry.coreapi.errorcase.case.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * LLM(에러 자동 구조화) 설정. prefix `llm`, per-profile YAML 에서 `${LLM_API_KEY}` 등으로 주입.
 * `enabled=false`(기본)면 실제 어댑터가 비활성 → NoOp 폴백만 돈다(키 없이도 부팅 OK).
 */
@ConfigurationProperties("llm")
data class LlmProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = "https://api.anthropic.com",
    val model: String = "claude-haiku-5-5",
    val anthropicVersion: String = "2023-06-01",
    val maxTokens: Int = 512,
    val timeoutMs: Long = 8000,
)
