package org.studieojavry.iamapi.auth.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(
    JwtProperties::class,
    OAuthProperties::class,
    AuthCookieProperties::class,
    AccountDeletionProperties::class,
    AvatarStorageProperties::class,
    AvatarS3Properties::class,
)
class AuthConfigRegistration
