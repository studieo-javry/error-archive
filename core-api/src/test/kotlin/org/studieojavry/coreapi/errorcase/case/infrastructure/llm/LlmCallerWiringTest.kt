package org.studieojavry.coreapi.errorcase.case.infrastructure.llm

import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource

/**
 * llm.enabled=true 일 때 호출 경로 배선 검증(컨텍스트 로드만, 실제 API 호출 없음).
 * 어댑터가 주입받는 AnthropicCaller 가 **CircuitBreaker 데코레이터**여야 하고(=@Primary),
 * 데코레이터는 실제 HTTP caller 를 감싼다. 빈 모호성/순환이 있으면 컨텍스트가 뜨지 않아 실패한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = ["llm.enabled=true", "llm.api-key=dummy-for-wiring"])
class LlmCallerWiringTest {

    @Autowired private lateinit var caller: AnthropicCaller

    @Test
    fun `AnthropicCaller 주입 빈은 CircuitBreaker 데코레이터다`() {
        assertInstanceOf(CircuitBreakerAnthropicCaller::class.java, caller)
    }
}
