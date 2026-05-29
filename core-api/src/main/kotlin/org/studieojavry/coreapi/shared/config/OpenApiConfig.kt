package org.studieojavry.coreapi.shared.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * core-api 의 OpenAPI 메타데이터 + 보안 스킴 정의.
 *
 * 인증: 모든 비즈니스 엔드포인트는 **internal JWT (RS256, aud=core-api)** 가 필요하다.
 * Swagger UI 의 "Authorize" 버튼으로 `X-Internal-Auth` 헤더에 토큰을 넣고 Try it out 하면 된다.
 * (실서비스에서는 게이트웨이가 토큰을 자동 발급/주입 — 여기 UI 는 게이트웨이를 우회한 직접 호출용)
 *
 * 로컬 토큰 발급:
 *   `scripts/mint-jwt.sh internal core-api <userId> USER`
 *   → 표준출력으로 토큰이 나오면 그대로 Authorize 입력칸에 붙여넣기(Bearer 접두사 없이).
 *   ⚠️ 수명 90초. 만들고 바로 써야 함.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun coreApiOpenApi(): OpenAPI {
        val schemeName = "X-Internal-Auth"
        return OpenAPI()
            .info(
                Info()
                    .title("Error Archive — core-api")
                    .version("v1")
                    .description(
                        """
                        에러케이스(에러 사례/스니펫/첨부) 도메인의 코어 API.

                        ## 인증
                        모든 비즈니스 엔드포인트는 `X-Internal-Auth` 헤더의 internal JWT 가 필요합니다.
                        Swagger UI 우상단의 **Authorize** 버튼을 눌러 토큰을 등록하세요.

                        ### 로컬 토큰 발급
                        ```bash
                        scripts/mint-jwt.sh internal core-api <userId> USER
                        ```
                        - 표준출력의 토큰을 그대로 입력(Bearer 접두사 X).
                        - 수명 90초.

                        ## 리소스 모델
                        - **에러 케이스(error-case)** — 본문/메타/스냅샷을 가진 사례. 스니펫·첨부를 markerId 로 연결.
                        - **코드 스니펫(error-snippet)** — 독립 생성 → markerId 발급 → 케이스에 연결. 본문에 `@snippet(markerId)` 로 인라인 임베드 가능.
                        - **첨부(error-attachment)** — 파일 업로드 → markerId 발급 → 케이스에 연결. 본문에 `@attach(markerId)`.

                        ## 사용 흐름 (등록)
                        1. (선택) `POST /error-snippets`, `POST /error-attachments` 로 스니펫/첨부를 먼저 생성 → markerId 수령.
                        2. `POST /error-cases` 본문에 `snippetMarkerIds`/`attachmentMarkerIds` 로 연결.

                        ## 사용 흐름 (수정)
                        - `PATCH /error-cases/{id}` — 케이스 본문·메타 + **스니펫/첨부 연결 집합** 변경(선언형: 보낸 marker 집합이 최종 상태).
                        - `PATCH /error-snippets/{markerId}` — 스니펫 **내용** 수정. markerId 는 유지되므로 본문 인라인 참조 안 깨짐.
                        """.trimIndent()
                    )
            )
            .addServersItem(Server().url("http://localhost:8081").description("로컬(게이트웨이 우회 직접 호출)"))
            .components(
                Components().addSecuritySchemes(
                    schemeName,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("X-Internal-Auth")
                        .description("internal JWT (RS256, aud=core-api). 로컬은 `scripts/mint-jwt.sh internal core-api <userId> USER`.")
                )
            )
            // 기본적으로 모든 엔드포인트가 이 스킴을 요구한다고 표시(개별 엔드포인트에서 해제 가능).
            .addSecurityItem(SecurityRequirement().addList(schemeName))
    }
}
