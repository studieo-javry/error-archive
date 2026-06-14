package org.studieojavry.notiapi.shared.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * iam-api 호출 설정 — noti-api 가 멘션 알림 발송 시 사용자 contact 조회 시 사용.
 * local 에서는 gateway 가 라우팅하지 않는 `/internal/...` 를 위해 *직접* iam-api 에 붙는다.
 */
@ConfigurationProperties(prefix = "noti.iam-api")
data class IamApiProperties(
    val baseUrl: String = "http://localhost:8080",
)
