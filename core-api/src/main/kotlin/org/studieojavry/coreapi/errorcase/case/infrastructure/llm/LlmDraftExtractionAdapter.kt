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
    // Anthropic JSON 조립/파싱 전용(앱 설정 불필요). 앱 기본 직렬화는 Jackson 3(tools.jackson)이라
    // Jackson 2 ObjectMapper 빈이 없으므로 **기본값으로 자체 생성** — Kotlin 옵셔널 파라미터라 Spring 은
    // 빈이 없으면 이 기본값을 쓴다(= llm.enabled=true 기동 시 NoSuchBean 방지). 테스트는 om 을 직접 주입.
    private val om: ObjectMapper = ObjectMapper(),
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
                "tags" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "소문자 태그 최대 5개(예외 유형·기술·도메인)"),
                "severity" to mapOf(
                    "type" to "string", "enum" to listOf("S1", "S2", "S3", "S4"),
                    "description" to "영향도 판단(반드시 하나): S1=서비스 중단/장애(OOM·커넥션 풀 고갈 등), S2=기능 저하/부분 실패(NPE·타임아웃 등), S3=경미(검증·상태 오류), S4=정보/경고",
                ),
                "confidence" to mapOf("type" to "number"),
            ),
            "required" to listOf("title", "summary", "tags", "severity", "confidence"),
        )
        val userText = buildString {
            if (props.fewShot) append(FEW_SHOT_PREFIX)
            append("다음 에러 로그를 구조화해줘. 로그에 없는 내용은 추측하지 말고 null/빈 배열로 둬.\n")
            input.exceptionClassHint?.let { append("정규식이 추출한 예외 클래스 후보: ").append(it).append('\n') }
            append("---\n").append(input.maskedPaste)
        }
        // 모델군별 구조화 전략 차이:
        //  - 강제 가능 모델(Haiku/Fable 등): tool_choice 로 extract_error_draft 를 **반드시** 쓰게 해 출력을 스키마에 가둔다.
        //  - 확장사고 모델(Sonnet/Opus 5 등): 강제 tool_choice(tool/any) 를 지원하지 않음 → auto 로 두고, system 으로 툴 사용을 강하게 유도.
        //    (tool_use 가 없으면 parseResponse 가 텍스트 JSON 을 폴백 파싱 → 동일한 신뢰성 확보)
        val toolChoice: Map<String, Any> =
            if (supportsForcedToolChoice(props.model)) mapOf("type" to "tool", "name" to TOOL_NAME)
            else mapOf("type" to "auto")
        val body = mapOf(
            // temperature 는 Haiku 5.5 등 신모델에서 deprecated → 보내지 않는다(모델 기본값). tool use 가 이미 출력을 구조로 가둬 결정성은 충분.
            "model" to props.model,
            "max_tokens" to props.maxTokens,
            "system" to "너는 에러 로그를 구조화하는 보조자다. 반드시 extract_error_draft 툴만 호출해 답한다. " +
                "exceptionClass·summary 는 로그에 있는 사실만(없으면 null). " +
                "severity 는 에러의 영향도를 판단해 S1~S4 중 반드시 하나를 고른다(장애성=S1, 기능저하=S2, 경미=S3, 정보=S4). " +
                "tags 는 소문자 최대 5개.",
            "messages" to listOf(mapOf("role" to "user", "content" to userText)),
            "tools" to listOf(
                mapOf("name" to TOOL_NAME, "description" to "에러 로그에서 제목·예외·요약·태그·심각도를 추출", "input_schema" to schema),
            ),
            "tool_choice" to toolChoice,
        )
        return om.writeValueAsString(body)
    }

    /** 응답 content[] 에서 tool_use(extract_error_draft) 블록의 input 을 Output 으로. 없으면 텍스트 JSON 폴백, 그래도 없으면 null. */
    private fun parseResponse(json: String): DraftExtractionPort.Output? {
        val root = om.readTree(json)
        val block = root.path("content").firstOrNull {
            it.path("type").asText() == "tool_use" && it.path("name").asText() == TOOL_NAME
        }
        val inp: JsonNode = when {
            block != null -> block.path("input")
            // 폴백: 확장사고 모델(auto)이 tool 대신 텍스트로 JSON 을 낸 경우 본문에서 객체만 추출해 파싱.
            else -> {
                val text = root.path("content")
                    .firstOrNull { it.path("type").asText() == "text" }
                    ?.path("text")?.asText() ?: return null
                val jsonObj = Regex("""\{.*\}""", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: return null
                runCatching { om.readTree(jsonObj) }.getOrNull() ?: return null
            }
        }
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

        /**
         * 강제 tool_choice(`{type:"tool"}` / `{type:"any"}`) 지원 여부.
         * Haiku·Fable 등 비(非)확장사고 모델은 강제 지원 → 스키마 강제. Sonnet·Opus(확장사고)는 미지원 → auto.
         * (실측: claude-sonnet-5-5 는 강제 시 `type "tool" and "any" are not supported for this model` 로 전건 실패)
         */
        fun supportsForcedToolChoice(model: String): Boolean {
            val m = model.lowercase()
            return m.contains("haiku") || m.contains("fable")
        }

        /**
         * few-shot 예시 — **eval 데이터셋과 겹치지 않는** 샘플로, severity 양 끝(S1·S4)과
         * null exceptionClass(스택트레이스 아닌 WARN 로그) 처리 기준을 보여준다.
         * 형식·판단 기준만 전달하고 내용 복붙은 금지시킨다(과적합 방지).
         */
        private val FEW_SHOT_PREFIX = """
            아래는 올바른 구조화 예시 2개다. 형식과 판단 기준만 참고하고, 내용을 그대로 복사하지 마라.

            [예시1 입력]
            redis.clients.jedis.exceptions.JedisConnectionException: Failed connecting to host redis:6379
            ${"\t"}at redis.clients.jedis.Connection.connect(Connection.java:204)
            ${"\t"}at com.shop.session.SessionStore.load(SessionStore.java:33)
            [예시1 출력]
            {"title":"Redis 연결 실패로 세션 로드 불가","exceptionClass":"redis.clients.jedis.exceptions.JedisConnectionException","summary":"SessionStore 가 redis:6379 연결에 실패해 세션을 불러오지 못함","tags":["redis","connection-refused","session","jedis","infra"],"severity":"S1","confidence":0.9}

            [예시2 입력]
            2026-10-20 09:15:00 WARN  c.shop.config.LegacyApiFilter : 'X-Legacy-Token' header is deprecated and will be removed in v2; use Authorization instead
            [예시2 출력]
            {"title":"X-Legacy-Token 헤더 deprecated 경고","exceptionClass":null,"summary":"LegacyApiFilter 가 곧 제거될 X-Legacy-Token 사용을 경고","tags":["deprecation","warning","config","auth","legacy"],"severity":"S4","confidence":0.8}

            ---
        """.trimIndent() + "\n"
    }
}
