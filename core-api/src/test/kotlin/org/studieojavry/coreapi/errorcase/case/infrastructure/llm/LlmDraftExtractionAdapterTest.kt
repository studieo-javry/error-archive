package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.studieojavry.coreapi.errorcase.case.application.port.DraftExtractionPort
import org.studieojavry.coreapi.errorcase.case.config.LlmProperties

/**
 * LLM 어댑터 단위 테스트 — 실제 Anthropic 호출 없이 가짜 caller 로 요청 빌드·tool_use 파싱·폴백을 검증.
 */
class LlmDraftExtractionAdapterTest {

    private val om = ObjectMapper()
    private val props = LlmProperties(enabled = true, apiKey = "test-key", model = "claude-haiku-5-5")
    private fun adapter(caller: AnthropicCaller) = LlmDraftExtractionAdapter(props, caller, om)

    private val input = DraftExtractionPort.Input(
        maskedPaste = "NullPointerException at com.shop.OrderService",
        exceptionClassHint = "java.lang.NullPointerException",
        workspaceTags = emptyList(),
    )

    private val toolUseResponse = """
        {"content":[
          {"type":"text","text":"ok"},
          {"type":"tool_use","name":"extract_error_draft","input":{
             "title":"주문 합산 NPE","exceptionClass":null,"summary":"쿠폰 null 로 NPE",
             "tags":["npe","order"],"severity":"S2","confidence":0.82}}
        ]}
    """.trimIndent()

    @Test
    fun `tool_use 응답을 Output 으로 파싱한다`() {
        val out = adapter { toolUseResponse }.extract(input)!!

        assertEquals("주문 합산 NPE", out.title)
        assertNull(out.exceptionClass)
        assertEquals("쿠폰 null 로 NPE", out.summary)
        assertEquals(listOf("npe", "order"), out.tags)
        assertEquals("S2", out.severity)
        assertEquals(0.82, out.confidence, 1e-9)
    }

    @Test
    fun `요청 본문이 JSON 스키마 강제(tool use) 형태로 빌드된다`() {
        var sent = ""
        adapter { req -> sent = req; toolUseResponse }.extract(input)
        val root = om.readTree(sent)

        assertEquals("claude-haiku-5-5", root.path("model").asText())
        assertTrue(root.path("temperature").isMissingNode, "temperature 는 신모델 deprecated → 보내지 않아야 함")
        assertEquals("extract_error_draft", root.path("tool_choice").path("name").asText())
        val severityEnum = root.path("tools").path(0).path("input_schema").path("properties").path("severity").path("enum")
        assertTrue(severityEnum.toString().contains("S2"), "severity enum 이 스키마에 없음: $severityEnum")
        val userContent = root.path("messages").path(0).path("content").asText()
        assertTrue(userContent.contains("NullPointerException at com.shop.OrderService"), "마스킹 paste 누락")
        assertTrue(userContent.contains("java.lang.NullPointerException"), "예외 힌트 누락")
    }

    @Test
    fun `few-shot off 면 예시를 넣지 않고, on 이면 예시를 선주입한다`() {
        var plainSent = ""
        adapter { req -> plainSent = req; toolUseResponse }.extract(input)
        val plainUser = om.readTree(plainSent).path("messages").path(0).path("content").asText()
        assertTrue(!plainUser.contains("예시1 입력"), "few-shot off 인데 예시가 들어감")

        var fsSent = ""
        val fsProps = props.copy(fewShot = true)
        LlmDraftExtractionAdapter(fsProps, { req -> fsSent = req; toolUseResponse }, om).extract(input)
        val fsUser = om.readTree(fsSent).path("messages").path(0).path("content").asText()
        assertTrue(fsUser.contains("예시1 입력") && fsUser.contains("예시2 입력"), "few-shot on 인데 예시가 없음")
        assertTrue(fsUser.contains("\"severity\":\"S1\"") && fsUser.contains("\"severity\":\"S4\""), "예시가 S1·S4 기준을 보여주지 않음")
        assertTrue(fsUser.indexOf("예시1 입력") < fsUser.indexOf(input.maskedPaste), "예시가 실제 로그보다 뒤에 옴")
    }

    @Test
    fun `tool_use 블록이 없으면 null (폴백 유도)`() {
        val resp = """{"content":[{"type":"text","text":"잘 모르겠어요"}]}"""
        assertNull(adapter { resp }.extract(input))
    }

    @Test
    fun `깨진 JSON 이면 null`() {
        assertNull(adapter { "not json {{{" }.extract(input))
    }

    @Test
    fun `호출이 예외를 던지면 null (서킷오픈·타임아웃 가정)`() {
        assertNull(adapter { throw RuntimeException("timeout") }.extract(input))
    }
}
