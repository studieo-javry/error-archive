package org.studieojavry.insightapi.activity.infrastructure.iam

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.studieojavry.insightapi.activity.application.port.UserSignupPort
import java.time.Instant

/**
 * iam-api `/internal/users/signups?since=&limit=` 호출 — 최근 가입 코호트(비게스트).
 *
 * 실패/타임아웃 시 **빈 목록**(활성화 지표만 건너뜀). gateway 미라우팅 internal 엔드포인트.
 * [IamUserTimezoneAdapter] 와 같은 `iamApiRestClient` 재사용.
 */
@Component
class IamUserSignupAdapter(
    @Qualifier("iamApiRestClient") private val iamApi: RestClient,
) : UserSignupPort {

    private val log = KotlinLogging.logger {}

    override fun recentSignups(since: Instant, limit: Int): List<UserSignupPort.Signup> =
        try {
            iamApi.get()
                .uri { b ->
                    b.path("/internal/users/signups")
                        .queryParam("since", since.toString())
                        .queryParam("limit", limit)
                        .build()
                }
                .retrieve()
                .body(object : ParameterizedTypeReference<List<SignupDto>>() {})
                ?.map { UserSignupPort.Signup(it.userId, it.createdAt) }
                ?: emptyList()
        } catch (e: Exception) {
            log.warn(e) { "iam-api signups lookup failed — activation metrics skipped this run" }
            emptyList()
        }

    private data class SignupDto(val userId: Long, val createdAt: Instant)
}
