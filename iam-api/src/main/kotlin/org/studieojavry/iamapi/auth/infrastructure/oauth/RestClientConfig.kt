package org.studieojavry.iamapi.auth.infrastructure.oauth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
class RestClientConfig {

    @Bean("oauthRestClient")
    fun oauthRestClient(): RestClient {
        val factory = JdkClientHttpRequestFactory().apply {
            setReadTimeout(Duration.ofSeconds(5))
        }
        return RestClient.builder()
            .requestFactory(factory)
            .build()
    }
}