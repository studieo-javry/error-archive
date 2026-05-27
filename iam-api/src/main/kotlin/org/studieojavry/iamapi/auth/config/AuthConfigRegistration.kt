package org.studieojavry.iamapi.auth.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(
    JwtProperties::class,
    AuthCookieProperties::class,
    OAuthProperties::class,
    AvatarStorageProperties::class,
    AccountDeletionProperties::class,
    org.studieojavry.iamapi.workspace.config.InvitationEmailProperties::class,
    org.studieojavry.iamapi.workspace.config.WorkspaceProperties::class,
)
class AuthConfigRegistration
