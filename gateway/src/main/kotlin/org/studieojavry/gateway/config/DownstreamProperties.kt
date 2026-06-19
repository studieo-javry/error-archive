package org.studieojavry.gateway.config

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * 다운스트림 서비스 base-url. RouteConfig 가 `.before(uri(...))` 로 사용한다.
 *
 * G2: 과거엔 RouteConfig 에 `http://localhost:80xx` 가 하드코딩되어 있어 stg/prod 로 못 올렸다.
 *     이제 환경별(local/stg/prod)로 주입 — k8s 에선 Service DNS(`http://core-api:8081` 등).
 *
 * fail-fast: 미해소 `${ENV}` placeholder 는 http(s) URL 패턴에 안 맞으므로 @Pattern 이 부팅을 막는다.
 *   기본값을 두지 않고(그리고 base application.yml 에도 두지 않고) local/stg/prod yml 에 concrete 값을
 *   둬야 placeholder 가 기본값으로 조용히 폴백하는 것을 방지한다.
 */
@Validated
@ConfigurationProperties(prefix = "gateway.downstream")
data class DownstreamProperties(
    @field:Pattern(regexp = "^https?://.+", message = "gateway.downstream.iam-api 는 절대 http(s) URL — 환경변수 주입 필수")
    val iamApi: String,
    @field:Pattern(regexp = "^https?://.+", message = "gateway.downstream.core-api 는 절대 http(s) URL — 환경변수 주입 필수")
    val coreApi: String,
    @field:Pattern(regexp = "^https?://.+", message = "gateway.downstream.noti-api 는 절대 http(s) URL — 환경변수 주입 필수")
    val notiApi: String,
    @field:Pattern(regexp = "^https?://.+", message = "gateway.downstream.insight-api 는 절대 http(s) URL — 환경변수 주입 필수")
    val insightApi: String,
    @field:Pattern(regexp = "^https?://.+", message = "gateway.downstream.publish-api 는 절대 http(s) URL — 환경변수 주입 필수")
    val publishApi: String,
)
