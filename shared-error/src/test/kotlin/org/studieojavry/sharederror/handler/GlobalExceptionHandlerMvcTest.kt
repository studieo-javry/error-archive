package org.studieojavry.sharederror.handler

import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.sharederror.masking.ErrorMaskingPolicy
import org.studieojavry.sharederror.trace.TraceIdAccessor

/**
 * [GlobalExceptionHandler] 의 바인딩 예외 4xx 매핑 회귀 테스트.
 *
 * 숫자 경로/쿼리 변수에 비숫자가 오면 과거엔 Throwable fallback 으로 떨어져 500 이 되고,
 * 게이트웨이 서킷브레이커가 이를 실패로 집계해 전 라우트 503 을 유발했다.
 * 이제 400 으로 매핑되어야 한다(서킷브레이커는 5xx 만 실패로 센다).
 */
class GlobalExceptionHandlerMvcTest {

    @RestController
    class ProbeController {
        @GetMapping("/t/{id}")
        fun byId(@PathVariable id: Long): Map<String, Any> = mapOf("id" to id)

        @GetMapping("/t")
        fun byParam(@RequestParam n: Int): Map<String, Any> = mapOf("n" to n)
    }

    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ProbeController())
        .setControllerAdvice(GlobalExceptionHandler(TraceIdAccessor(null), ErrorMaskingPolicy(MockEnvironment())))
        .build()

    @Test
    fun `non-numeric path variable maps to 400 TYPE_MISMATCH, not 500`() {
        mvc.perform(get("/t/abc"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
    }

    @Test
    fun `decimal for a Long path variable maps to 400`() {
        mvc.perform(get("/t/1.5"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
    }

    @Test
    fun `non-numeric query param maps to 400 TYPE_MISMATCH`() {
        mvc.perform(get("/t").param("n", "xyz"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("TYPE_MISMATCH"))
    }

    @Test
    fun `missing required query param maps to 400 MISSING_PARAMETER`() {
        mvc.perform(get("/t"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
    }

    @Test
    fun `valid numeric path variable still succeeds`() {
        mvc.perform(get("/t/5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(5))
    }

    @Test
    fun `valid query param still succeeds`() {
        mvc.perform(get("/t").param("n", "3"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.n").value(3))
    }
}
