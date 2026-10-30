package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

/**
 * Anthropic Messages API 로 요청 본문(JSON)을 보내고 응답 본문(JSON)을 받는 얇은 HTTP 경계.
 *
 * HTTP 세부를 어댑터 로직에서 떼어내, 어댑터(요청 빌드·tool_use 파싱·검증)를
 * **실제 네트워크 없이 단위 테스트**할 수 있게 한다(가짜 caller 주입).
 */
fun interface AnthropicCaller {
    /** 실패(HTTP 에러·타임아웃)면 예외를 던진다. 어댑터가 잡아 null 폴백한다. */
    fun call(requestJson: String): String
}
