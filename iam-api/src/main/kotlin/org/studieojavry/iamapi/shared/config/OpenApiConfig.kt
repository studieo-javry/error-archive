package org.studieojavry.iamapi.shared.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * iam-api 의 OpenAPI 메타데이터 + 보안 스킴.
 *
 * 인증: `Authorization: Bearer <jwt>`.
 *  - 사용자 JWT (HS256, gateway 발급) — 일반 사용자 API.
 *  - 또는 internal JWT (RS256, gateway 발급) — 내부 서비스 호출.
 *  iam-api 의 `MultiIssuerJwtDecoder` 가 둘 다 받아준다.
 *
 * Swagger UI 의 "Authorize" 에 토큰을 넣고 Try it out 하면 된다.
 *
 * 로컬 토큰 발급:
 *   - 사용자: `scripts/mint-jwt.sh user <userId> USER`
 *   - 내부:  `scripts/mint-jwt.sh internal iam-api <userId> USER` (수명 90초)
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun iamApiOpenApi(): OpenAPI {
        val scheme = "bearer-jwt"
        return OpenAPI()
            .info(
                Info()
                    .title("Error Archive — iam-api")
                    .version("v1")
                    .description(
                        """
                        IAM(인증/인가) + 워크스페이스/소셜 그래프 도메인 API.

                        ## 인증
                        대부분의 엔드포인트는 `Authorization: Bearer <jwt>` 가 필요합니다.
                        Swagger UI 우상단의 **Authorize** 버튼에 토큰을 넣으세요(Bearer 접두사 제외).

                        ### 인증 없이 호출 가능(public)
                        - `POST /api/v1/auth/oauth/**` — OAuth 시작/콜백.
                        - `POST /api/v1/auth/refresh`, `/api/v1/auth/logout` — 리프레시 쿠키 기반.
                        - `GET /api/v1/users/{id}/follow-status|followers|following` — 공개 사회 그래프.
                        - `GET /api/v1/invitations/preview` — 초대 토큰 미리보기(가입 결정 전).

                        ## 토큰 발급(로컬)
                        ```bash
                        scripts/mint-jwt.sh user <userId> USER         # 사용자 JWT (HS256, 1h)
                        scripts/mint-jwt.sh internal iam-api <userId>  # 내부 JWT (RS256, 90s)
                        ```

                        ## 워크스페이스 권한 모델
                        - **READ(1)** — 조회만.
                        - **WRITE(2)** — 콘텐츠 작성(에러케이스 등).
                        - **ADMIN(3)** — 멤버/워크스페이스 관리.
                        """.trimIndent()
                    )
            )
            .addServersItem(Server().url("http://localhost:8080").description("iam-api 직접(게이트웨이 우회)"))
            .addServersItem(Server().url("http://localhost:8000").description("게이트웨이 경유"))
            .components(
                Components().addSecuritySchemes(
                    scheme,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Authorization: Bearer <jwt>. 사용자 JWT(HS256) 또는 internal JWT(RS256, aud=iam-api).")
                )
            )
            .addSecurityItem(SecurityRequirement().addList(scheme))
    }
}
