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
import org.studieojavry.gateway.filter.IpRateLimiter
import org.studieojavry.gateway.filter.RateLimitFilters

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
class RouteConfig(
    private val downstream: DownstreamProperties,
    private val ipRateLimiter: IpRateLimiter,
) {

    // ⚠️ Route Bean 순서가 매칭 우선순위. 더 구체적인 path를 먼저 정의해야 한다.
    //    예: `/api/v1/users/me/notification-settings` 는 `/api/v1/users/**` 보다 더 구체적이라
    //         notiApiRoute 가 iamApiRoute 위에 와야 함.

    @Bean
    fun notiApiRoute(): RouterFunction<ServerResponse> =
        route("noti-api")
            .route(
                path("/api/v1/users/me/notification-settings/**")
                    .or(path("/api/v1/users/me/notification-settings"))
                    .or(path("/api/v1/users/me/notifications/**"))
                    .or(path("/api/v1/users/me/notifications"))
                    .or(path("/api/v1/users/me/device-tokens/**"))
                    .or(path("/api/v1/users/me/device-tokens")),
                http()
            )
            .before(uri(downstream.notiApi))
            .filter(circuitBreaker("notiApiCB", "/__fallback/noti-api"))
            .filter(retry(2))
            .build()

    // insight-api 의 path 가 `/api/v1/users/**` (iam) 의 서브셋이므로 iamApiRoute 위에 두어야
    // /users/me/activity-grass 가 iam 으로 잘못 라우팅되지 않는다. notiApiRoute 와 같은 패턴.
    @Bean
    fun insightApiRoute(): RouterFunction<ServerResponse> =
        route("insight-api")
            .route(
                path("/api/v1/users/me/activity-grass/**")
                    .or(path("/api/v1/users/me/activity-grass"))
                    .or(path("/api/v1/users/*/activity-grass/**"))
                    .or(path("/api/v1/users/*/activity-grass"))
                    .or(path("/api/v1/users/me/kpis")),
                http()
            )
            .before(uri(downstream.insightApi))
            .filter(circuitBreaker("insightApiCB", "/__fallback/insight-api"))
            .filter(retry(2))
            .build()

    // core-api 도 `/api/v1/users/me/*` (홈 대시보드 위젯) 와 `/api/v1/users/{id}/recent-activities`
    // (공개 프로필) 를 갖는다. iam 의 `/api/v1/users/**` 서브셋이므로 noti/insight 처럼 iamApiRoute
    // 위에 carve-out. 이게 없으면 위젯 호출이 iam-api 로 잘못 라우팅되어 404 가 난다.
    // ⚠️ MeController / PublicProfileController 에 me-scoped endpoint 를 추가하면 여기도 같이 추가할 것.
    @Bean
    fun coreApiUserRoute(): RouterFunction<ServerResponse> =
        route("core-api-user")
            .route(
                path("/api/v1/users/me/recent-active-cases")
                    .or(path("/api/v1/users/me/recent-activities"))
                    .or(path("/api/v1/users/me/watchlist"))
                    .or(path("/api/v1/users/me/watchlist-feed"))
                    .or(path("/api/v1/users/me/following-feed"))
                    .or(path("/api/v1/users/me/suggested-followees"))
                    .or(path("/api/v1/users/*/recent-activities")),
                http()
            )
            .before(uri(downstream.coreApi))
            .filter(circuitBreaker("coreApiCB", "/__fallback/core-api"))
            .filter(retry(2))
            .build()

    @Bean
    fun iamApiRoute(): RouterFunction<ServerResponse> =
        route("iam-api")
            .route(
                path("/api/v1/auth/**")
                    .or(path("/api/v1/users/**"))
                    .or(path("/api/v1/workspaces/**"))
                    .or(path("/api/v1/invitations/**"))
                    .or(path("/avatars/**")),
                http()
            )
            .before(uri(downstream.iamApi))
            .filter(circuitBreaker("iamApiCB", "/__fallback/iam-api"))
            .filter(retry(2))
            .build()

    // ── 공개(무인증) publish 경로 — per-IP rate limit 로 익명 DoS 방어 ──────────────────
    // ⚠️ 아래 두 공개 라우트는 publishApiRoute(관리 API) 위에 선언해야 한다. by-slug 는
    //    `/api/v1/publishments/**` 의 서브셋이라, 더 구체적인 이 라우트가 먼저 매칭돼야 함.
    //    관리 API(create/update/delete/list/me)는 인증(JWT) + per-user 라 rate limit 미적용.

    /**
     * 렌더 비용이 큰 공개 export — PDF(OpenHTMLToPdf 풀 렌더) / MD. 엄격 제한(버스트 5, 10초당 1회).
     * 1cpu 박스에서 익명이 PDF 를 연타하면 CPU 고갈 → 여기서 downstream 전에 429 로 차단.
     */
    @Bean
    fun publishPublicExportRoute(): RouterFunction<ServerResponse> =
        route("publish-public-export")
            .route(
                path("/p/*.pdf")
                    .or(path("/api/v1/publishments/by-slug/*/export.pdf"))
                    .or(path("/api/v1/publishments/by-slug/*/export.md")),
                http()
            )
            .before(uri(downstream.publishApi))
            .filter(RateLimitFilters.perIp(ipRateLimiter, "publish-export", capacity = 5.0, refillPerSec = 0.1))
            .filter(circuitBreaker("publishApiCB", "/__fallback/publish-api"))
            .filter(retry(2))
            .build()

    /**
     * 공개 페이지(HTML) + 첨부 파일. 넉넉한 제한(버스트 120, 초당 10)으로
     * 정상 열람(한 페이지가 이미지 다수 요청)은 통과, 대량 스크래핑/봇만 억제.
     *
     * 참고: 예전엔 여기 by-slug 하위 전체도 포함해 공개 JSON 조회를 rate-limit 했으나, 그 GET 엔드포인트가
     *       제거됨(2026-07-31). 남은 공개 by-slug 는 export.pdf, export.md 뿐이고 그건 위 export 라우트가
     *       담당. 관리용 by-slug(PATCH, POST, DELETE)는 아래 publishApiRoute(인증)로 폴백된다.
     */
    @Bean
    fun publishPublicRoute(): RouterFunction<ServerResponse> =
        route("publish-public")
            .route(
                path("/p/**"),
                http()
            )
            .before(uri(downstream.publishApi))
            .filter(RateLimitFilters.perIp(ipRateLimiter, "publish-public", capacity = 120.0, refillPerSec = 10.0))
            .filter(circuitBreaker("publishApiCB", "/__fallback/publish-api"))
            .filter(retry(2))
            .build()

    @Bean
    fun publishApiRoute(): RouterFunction<ServerResponse> =
        route("publish-api")
            .route(
                path("/api/v1/publishments/**")
                    .or(path("/api/v1/publishments")),
                http()
            )
            .before(uri(downstream.publishApi))
            .filter(circuitBreaker("publishApiCB", "/__fallback/publish-api"))
            .filter(retry(2))
            .build()

    @Bean
    fun publishDocsRoute(): RouterFunction<ServerResponse> =
        route("publish-docs")
            .GET("/api-docs/publish-api", http())
            .before(uri(downstream.publishApi))
            .before(setPath("/v3/api-docs"))
            .build()

    @Bean
    fun insightDocsRoute(): RouterFunction<ServerResponse> =
        route("insight-docs")
            .GET("/api-docs/insight-api", http())
            .before(uri(downstream.insightApi))
            .before(setPath("/v3/api-docs"))
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
            .before(uri(downstream.coreApi))
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
            .before(uri(downstream.iamApi))
            .before(setPath("/v3/api-docs"))
            .build()

    @Bean
    fun notiDocsRoute(): RouterFunction<ServerResponse> =
        route("noti-docs")
            .GET("/api-docs/noti-api", http())
            .before(uri(downstream.notiApi))
            .before(setPath("/v3/api-docs"))
            .build()

    @Bean
    fun coreDocsRoute(): RouterFunction<ServerResponse> =
        route("core-docs")
            .GET("/api-docs/core-api", http())
            .before(uri(downstream.coreApi))
            .before(setPath("/v3/api-docs"))
            .build()
}
