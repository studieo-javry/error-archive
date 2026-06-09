package org.studieojavry.sharederror.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.studieojavry.sharederror.security.AuthFailureMonitor
import org.studieojavry.sharederror.security.CaffeineAuthFailureMonitor
import org.studieojavry.sharederror.security.ProblemDetailAccessDeniedHandler
import org.studieojavry.sharederror.security.ProblemDetailAuthenticationEntryPoint
import org.studieojavry.sharederror.trace.TraceIdAccessor
import tools.jackson.databind.ObjectMapper

/**
 * shared-error 의 bean 자동 등록.
 *
 * @ConditionalOnMissingBean — 소비 서비스가 자기 구현으로 *override 가능*.
 *   예: 외부 WAF 도입 시 AuthFailureMonitor 의 다른 구현체를 @Bean 으로 등록하면 substitute.
 */
@Configuration
class SharedErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun authFailureMonitor(): AuthFailureMonitor = CaffeineAuthFailureMonitor()

    @Bean
    @ConditionalOnMissingBean
    fun problemDetailAuthenticationEntryPoint(
        objectMapper: ObjectMapper,
        traceIdAccessor: TraceIdAccessor,
        failureMonitor: AuthFailureMonitor,
    ): ProblemDetailAuthenticationEntryPoint =
        ProblemDetailAuthenticationEntryPoint(objectMapper, traceIdAccessor, failureMonitor)

    @Bean
    @ConditionalOnMissingBean
    fun problemDetailAccessDeniedHandler(
        objectMapper: ObjectMapper,
        traceIdAccessor: TraceIdAccessor,
    ): ProblemDetailAccessDeniedHandler =
        ProblemDetailAccessDeniedHandler(objectMapper, traceIdAccessor)
}
