package org.studieojavry.notiapi.shared.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * noti-api 의 OpenAPI 메타데이터 + 보안 스킴.
 *
 * **인증 모델 — core-api 와 동일**:
 *  - 게이트웨이가 발급한 *internal JWT* (RS256, `aud=noti-api`) 를 `Authorization: Bearer <token>` 으로 전달
 *  - principal = `userId: Long` (토큰의 `sub`)
 *
 * 게이트웨이가 user JWT → internal JWT 변환을 담당하므로, *실제 운영* 에서는 클라이언트가
 * `http://localhost:8000/api/v1/...` 로 호출하면 게이트웨이가 자동으로 내부 토큰을 주입한다.
 *
 * Swagger UI 에서 직접(8082) 호출하려면 internal JWT 가 필요:
 *   `scripts/mint-jwt.sh internal noti-api <userId> USER`
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun notiApiOpenApi(): OpenAPI {
        val scheme = "bearer-jwt"
        return OpenAPI()
            .info(
                Info()
                    .title("Error Archive — noti-api")
                    .version("v1")
                    .description(
                        """
                        알림 도메인 마이크로서비스 — 사용자 알림 설정 + (향후) 발송 정책 / Kafka consumer.

                        ## 책임
                        - **알림 설정 (구현됨)**: 채널(`email`/`inApp`) × 카테고리 매트릭스 + master switch.
                          Security alerts 는 양 채널 모두 강제 on (응답에 `securityAlerts:true` 고정).
                        - **알림 발송 (예정)**: `errorcase.events` Kafka consumer + email/in-app sender adapter.
                        - **user lifecycle 동기화 (예정)**: `iam.user-events` consumer 로 row 정리.

                        ## 인증
                        모든 endpoint 는 **게이트웨이가 발급한 internal JWT** 가 필요 (`Authorization: Bearer <token>`).
                        principal = `userId: Long` (토큰의 `sub`).

                        ### 직접 호출 시 토큰 발급
                        ```bash
                        scripts/mint-jwt.sh internal noti-api <userId> USER  # RS256, 90s
                        ```

                        ### 게이트웨이 경유 시
                        클라이언트는 *user JWT* (HS256) 를 보내고, 게이트웨이가 `aud=noti-api` 인 internal JWT 로
                        교환한 후 본 서비스로 전달한다 — 클라이언트는 토큰 종류를 신경 쓸 필요 없음.

                        ## 카테고리 키
                        - **email**: `mentions / replies / newFollowers / workspaceInvitations / weeklyDigest / productAnnouncements`
                        - **inApp**: `mentions / replies / newFollowers / workspaceInvitations`
                        - **공통(강제 on)**: `securityAlerts` — patch 페이로드에 포함 불가
                        """.trimIndent()
                    )
            )
            .addServersItem(Server().url("http://localhost:8082").description("noti-api 직접(게이트웨이 우회 — internal JWT 필요)"))
            .addServersItem(Server().url("http://localhost:8000").description("게이트웨이 경유(user JWT 사용 가능)"))
            .components(
                Components().addSecuritySchemes(
                    scheme,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Authorization: Bearer <jwt>. 게이트웨이 경유면 user JWT, 직접 호출이면 internal JWT(aud=noti-api).")
                )
            )
            .addSecurityItem(SecurityRequirement().addList(scheme))
    }
}
