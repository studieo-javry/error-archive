package org.studieojavry.sharederror.handler

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.studieojavry.sharederror.masking.ErrorMaskingPolicy
import org.studieojavry.sharederror.trace.TraceIdAccessor

/**
 * [GlobalExceptionHandler] 의 메서드 파라미터 제약(@Min/@Max 등) 위반 → 400 회귀 테스트.
 *
 * 페이지 크기(size/limit) 에 bound 를 걸어도, 위반 시 HandlerMethodValidationException 이
 * 미처리면 500 → gateway 서킷브레이커 오염(타입불일치와 동일 class). 400 으로 매핑돼야 한다.
 */
class ParamValidationMvcTest {

    @RestController
    class ProbeController {
        @GetMapping("/p")
        fun paged(@RequestParam(defaultValue = "20") @Min(1) @Max(100) size: Int): Map<String, Any> =
            mapOf("size" to size)
    }

    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ProbeController())
        .setControllerAdvice(GlobalExceptionHandler(TraceIdAccessor(null), ErrorMaskingPolicy(MockEnvironment())))
        .build()

    @Test
    fun `size below min maps to 400 VALIDATION_FAILED, not 500`() {
        mvc.perform(get("/p").param("size", "0"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `negative size maps to 400`() {
        mvc.perform(get("/p").param("size", "-1"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `size above max maps to 400`() {
        mvc.perform(get("/p").param("size", "99999"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `in-range size still succeeds`() {
        mvc.perform(get("/p").param("size", "50"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(50))
    }
}
