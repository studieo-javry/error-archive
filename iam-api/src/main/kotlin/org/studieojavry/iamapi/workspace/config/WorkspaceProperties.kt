package org.studieojavry.iamapi.workspace.config

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "iam.workspace")
data class WorkspaceProperties(
    /** FE에서 초대 토큰을 처리하는 베이스 URL. 백엔드는 `${base}/{token}` 형태로 acceptUrl 생성. */
    @field:NotBlank
    val inviteBaseUrl: String,

    @field:Min(1)
    val defaultInvitationTtlHours: Int = 24 * 7,

    @field:Min(1)
    val maxInvitationTtlHours: Int = 24 * 30,

    /** 사용자가 단일 워크스페이스 안에서 가질 수 있는 멤버 수 상한(임시 정책). */
    @field:Min(1)
    val maxMembersPerWorkspace: Int = 200
)

@Configuration
@EnableConfigurationProperties(WorkspaceProperties::class)
class WorkspaceConfigRegistration