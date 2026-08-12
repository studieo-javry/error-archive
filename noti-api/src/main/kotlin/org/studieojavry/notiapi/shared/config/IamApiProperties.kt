package org.studieojavry.notiapi.shared.config

import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * iam-api 호출 설정 — noti-api 가 멘션 알림 발송 시 사용자 contact 조회 시 사용.
 * local 에서는 gateway 가 라우팅하지 않는 `/internal/...` 를 위해 *직접* iam-api 에 붙는다.
 *
 * fail-fast: 절대 http(s) URL 이어야 한다. prod 에서 `${NOTI_IAM_API_BASE_URL}` 미설정 시 Binder 는
 * 미해소 placeholder 를 *리터럴 문자열*로 바인딩하므로(@NotBlank 로는 못 잡음) URL 패턴으로 걸러
 * 부팅을 중단시킨다(안 그러면 조용히 localhost → 이메일 알림 미발송). local=application-local.yml,
 * test=application-test.yml 이 concrete 값(iam-api 포트 8080) 제공.
 */
@Validated
@ConfigurationProperties(prefix = "noti.iam-api")
data class IamApiProperties(
    @field:Pattern(
        regexp = "^https?://.+",
        message = "noti.iam-api.base-url 은 절대 http(s) URL 이어야 함 — prod 는 NOTI_IAM_API_BASE_URL 필수",
    )
    val baseUrl: String,
)
