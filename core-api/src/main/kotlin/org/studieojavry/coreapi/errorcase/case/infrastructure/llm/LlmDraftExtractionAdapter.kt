package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties

/**
 * 실제 LLM(Anthropic Claude Haiku 5.5) 기반 구조화 어댑터.
 *
 * - **JSON Schema 강제**: Anthropic *tool use* 로 `extract_error_draft` 툴의 `input_schema` 를 강제하고
 *   `tool_choice` 로 그 툴을 반드시 쓰게 해 응답을 스키마에 가둔다.
 * - **실패는 null**: HTTP 실패·tool_use 부재·파싱 실패 등 어떤 문제든 null 반환 → UseCase 가 정규식 폴백.
 *   (최종 값 검증 — enum/길이/개수 — 은 UseCase 가 한 번 더 한다)
 * - `llm.enabled=true` 일 때만 빈 등록되고, NoOp 보다 우선(@Primary).
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "llm", name = ["enabled"], havingValue = "true")
class LlmDraftExtractionAdapter(
    private val props: LlmProperties,
    private val caller: AnthropicCaller,
    private val om: ObjectMapper,
) : DraftExtractionPort {

    override fun extract(input: DraftExtractionPort.Input): DraftExtractionPort.Output? = runCatching {
        val response = caller.call(buildRequestJson(input))
        parseResponse(response)
    }.getOrNull()

    /** Anthropic Messages 요청 본문 — tool use 로 JSON 스키마 강제. */
    private fun buildRequestJson(input: DraftExtractionPort.Input): String {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "description" to "에러 한 줄 제목(한국어, ≤120자)"),
                "exceptionClass" to mapOf("type" to listOf("string", "null")),
                "summary" to mapOf("type" to listOf("string", "null"), "description" to "한 줄 요약(한국어, ≤200자)"),
                "tags" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "소문자 태그 최대 5개"),
                "severity" to mapOf("type" to listOf("string", "null"), "enum" to listOf("S1", "S2", "S3", "S4", null)),
                "confidence" to mapOf("type" to "number"),
            ),
            "required" to listOf("title", "summary", "tags", "confidence"),
        )
        val userText = buildString {
            append("다음 에러 로그를 구조화해줘. 로그에 없는 내용은 추측하지 말고 null/빈 배열로 둬.\n")
            input.exceptionClassHint?.let { append("정규식이 추출한 예외 클래스 후보: ").append(it).append('\n') }
            append("---\n").append(input.maskedPaste)
        }
        val body = mapOf(
            // temperature 는 Haiku 5.5 등 신모델에서 deprecated → 보내지 않는다(모델 기본값). tool use 가 이미 출력을 구조로 가둬 결정성은 충분.
            "model" to props.model,
            "max_tokens" to props.maxTokens,
            "system" to "너는 에러 로그를 구조화하는 보조자다. 반드시 extract_error_draft 툴만 호출해 답한다. summary 는 로그 범위 안에서만.",
            "messages" to listOf(mapOf("role" to "user", "content" to userText)),
            "tools" to listOf(
                mapOf("name" to TOOL_NAME, "description" to "에러 로그에서 제목·예외·요약·태그·심각도를 추출", "input_schema" to schema),
            ),
            "tool_choice" to mapOf("type" to "tool", "name" to TOOL_NAME),
        )
        return om.writeValueAsString(body)
    }

    /** 응답 content[] 에서 tool_use(extract_error_draft) 블록의 input 을 Output 으로. 없으면 null. */
    private fun parseResponse(json: String): DraftExtractionPort.Output? {
        val root = om.readTree(json)
        val block = root.path("content").firstOrNull {
            it.path("type").asText() == "tool_use" && it.path("name").asText() == TOOL_NAME
        } ?: return null
        val inp = block.path("input")
        return DraftExtractionPort.Output(
            title = inp.path("title").textOrNull(),
            exceptionClass = inp.path("exceptionClass").textOrNull(),
            summary = inp.path("summary").textOrNull(),
            tags = inp.path("tags").mapNotNull { it.textOrNull() },
            severity = inp.path("severity").textOrNull(),
            confidence = inp.path("confidence").asDouble(0.0),
        )
    }

    private fun JsonNode.textOrNull(): String? = if (isTextual) asText() else null

    companion object {
        private const val TOOL_NAME = "extract_error_draft"
    }
}
