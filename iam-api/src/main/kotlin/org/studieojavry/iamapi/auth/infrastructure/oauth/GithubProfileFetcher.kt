package org.studieojavry.iamapi.auth.infrastructure.oauth

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort.ProviderCredential
import org.studieojavry.iamapi.auth.application.port.SocialProfileFetcherPort.SocialProfile
import org.studieojavry.iamapi.auth.config.OAuthProperties
import org.studieojavry.iamapi.auth.domain.model.vo.SocialProvider

@Component
class GithubProfileFetcher(
    private val oauthProperties: OAuthProperties,
    @Qualifier("oauthRestClient") private val restClient: RestClient,
    cbFactory: CircuitBreakerFactory<*, *>
) : SocialProfileFetcherPort {

    private val log = KotlinLogging.logger {}

    /**
     * GitHub 의 모든 외부 호출이 같은 CB 를 공유.
     * GitHub 가 5xx/timeout 누적되면 OPEN → 즉시 fallback 으로 응답해
     * iam-api 의 thread/connection pool 이 잠기는 것을 방지.
     */
    private val cb = cbFactory.create("github")

    override fun supports(provider: SocialProvider): Boolean = provider == SocialProvider.GITHUB

    override fun fetch(credential: ProviderCredential): SocialProfile {
        val accessToken = when (credential) {
            is ProviderCredential.AuthorizationCode -> exchangeCodeForToken(credential)
            is ProviderCredential.IdToken -> throw IllegalArgumentException("github does not support id token credential")
        }

        val user = fetchUser(accessToken)
        val email = user.email ?: fetchPrimaryVerifiedEmail(accessToken)

        return SocialProfile(
            providerUserId = user.id.toString(),
            email = email,
            displayName = (user.name ?: user.login).take(100),
            profileUrl = user.htmlUrl,
            avatarUrl = user.avatarUrl
        )
    }

    private fun exchangeCodeForToken(credential: ProviderCredential.AuthorizationCode): String = cb.run(
        {
            val github = oauthProperties.github
            val form = LinkedMultiValueMap<String, String>().apply {
                add("client_id", github.clientId)
                add("client_secret", github.clientSecret)
                add("code", credential.code)
                add("redirect_uri", credential.redirectUri)
            }

            val response: TokenResponse = try {
                restClient.post()
                    .uri(github.tokenEndpoint)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse::class.java)
                    ?: throw IllegalStateException("github token response was empty")
            } catch (ex: RestClientResponseException) {
                // 5xx 는 CB 가 실패로 카운트하도록 다시 throw
                throw IllegalStateException("github token exchange failed: ${ex.statusCode}", ex)
            }

            if (response.error != null) {
                // GitHub 가 200 + error 본문(bad_verification_code 등)을 주는 경우.
                // 비즈니스 에러이므로 CB 가 실패로 카운트하면 안 됨 → 우리 자체 throw 하되
                // CB 입장에선 "성공한 호출" 로 본다 → 이를 위해 CB 외부에서 throw 하도록 분리하고 싶지만,
                // run-block 안에서 throw 하면 CB 가 실패로 잡으므로 GithubBusinessException 같은 별도 분기 필요.
                // 우선 단순화: business error 는 CB 의 ignoreException 으로 분리하거나 일단 실패로 카운트.
                throw GithubBusinessException("github token exchange error: ${response.error}")
            }
            response.accessToken
                ?: throw IllegalStateException("github token response missing access_token")
        },
        { throwable ->
            log.warn(throwable) { "github unavailable on token exchange, circuit breaker fallback" }
            throw GithubUnavailableException("github token exchange unavailable", throwable)
        }
    )

    private fun fetchUser(accessToken: String): GithubUser = cb.run(
        {
            restClient.get()
                .uri(oauthProperties.github.userEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(GithubUser::class.java)
                ?: throw IllegalStateException("github user response was empty")
        },
        { throwable ->
            log.warn(throwable) { "github unavailable on /user, circuit breaker fallback" }
            throw GithubUnavailableException("github user fetch unavailable", throwable)
        }
    )

    /**
     * primary email 조회는 best-effort. 실패 시 null 로 fallback (기존 동작 유지).
     * CB 는 호출 자체의 성공/실패만 트래킹.
     */
    private fun fetchPrimaryVerifiedEmail(accessToken: String): String? = cb.run(
        {
            val emails = restClient.get()
                .uri(oauthProperties.github.emailEndpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(Array<GithubEmail>::class.java)
                ?: emptyArray()
            emails.firstOrNull { it.primary && it.verified }?.email
        },
        { throwable ->
            log.warn(throwable) { "github unavailable on /user/emails, returning null (degraded)" }
            null
        }
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String?,
        @JsonProperty("token_type") val tokenType: String?,
        @JsonProperty("scope") val scope: String?,
        @JsonProperty("error") val error: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GithubUser(
        val id: Long,
        val login: String,
        val name: String?,
        val email: String?,
        @JsonProperty("html_url") val htmlUrl: String?,
        @JsonProperty("avatar_url") val avatarUrl: String?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class GithubEmail(
        val email: String,
        val primary: Boolean,
        val verified: Boolean
    )
}

class GithubUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class GithubBusinessException(message: String) : RuntimeException(message)
