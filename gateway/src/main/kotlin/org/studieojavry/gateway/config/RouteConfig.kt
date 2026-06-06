package org.studieojavry.gateway.config

import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.setPath
import org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri
import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions.circuitBreaker
import org.springframework.cloud.gateway.server.mvc.filter.RetryFilterFunctions.retry
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.function.RequestPredicates.path
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.ServerResponse

/**
 * Spring Cloud Gateway Server MVC 라우트 정의.
 *
 * yml 의 `spring.cloud.gateway.mvc.routes` 가 Spring Cloud 2025.1.x (gateway-server-webmvc 5.0)
 * 에서 인식되지 않는 이슈가 있어, Kotlin Bean 으로 명시 정의 — 컴파일 검증 + IDE 자동완성.
 *
 * Bean 기반 라우트는 MVC HandlerMapping 에 자동 등록되므로 Spring Security 의
 * requestMatchers 패턴 매칭도 정상 동작한다.
 *
 * SCG Server MVC 5.x API:
 *  - HandlerFunctions.http() 는 noargs — 실제 URI 는 `before(uri("..."))` 필터로 주입
 *  - circuitBreaker(name, fallbackPath) / retry(n) 는 표준 OOTB 필터
 */
@Configuration
class RouteConfig {

    @Bean
    fun iamApiRoute(): RouterFunction<ServerResponse> =
        route("iam-api")
            .route(
                path("/api/v1/auth/**")
                    .or(path("/api/v1/users/**"))
                    .or(path("/api/v1/workspaces/**"))
                    .or(path("/api/v1/invitations/**")),
                http()
            )
            .before(uri("http://localhost:8080"))
            .filter(circuitBreaker("iamApiCB", "/__fallback/iam-api"))
            .filter(retry(2))
            .build()

    @Bean
    fun coreApiRoute(): RouterFunction<ServerResponse> =
        route("core-api")
            .route(
                path("/api/v1/error-cases/**")
                    .or(path("/api/v1/error-attachments/**"))
                    .or(path("/api/v1/error-snippets/**"))
                    .or(path("/api/v1/step-attempt-types/**")),
                http()
            )
            .before(uri("http://localhost:8081"))
            .filter(circuitBreaker("coreApiCB", "/__fallback/core-api"))
            .filter(retry(2))
            .build()

    /**
     * Swagger UI aggregator 용 다운스트림 docs 프록시.
     * `/api-docs/iam-api` → iam-api:8080/v3/api-docs, `/api-docs/core-api` → core-api:8081/v3/api-docs.
     * 게이트웨이와 same-origin 으로 노출되어 swagger-ui 가 CORS 없이 스펙을 fetch.
     */
    @Bean
    fun iamDocsRoute(): RouterFunction<ServerResponse> =
        route("iam-docs")
            .GET("/api-docs/iam-api", http())
            .before(uri("http://localhost:8080"))
            .before(setPath("/v3/api-docs"))
            .build()

    @Bean
    fun coreDocsRoute(): RouterFunction<ServerResponse> =
        route("core-docs")
            .GET("/api-docs/core-api", http())
            .before(uri("http://localhost:8081"))
            .before(setPath("/v3/api-docs"))
            .build()
}
