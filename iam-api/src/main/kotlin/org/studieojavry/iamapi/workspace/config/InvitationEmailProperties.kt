package org.studieojavry.iamapi.workspace.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "iam.email")
data class InvitationEmailProperties(
    /** smtp | logging — 어느 어댑터를 활성화할지 결정. */
    val provider: String = "logging",

    /** 발신자 메일 주소 (예: noreply@error-archive.dev). */
    @field:NotBlank
    val fromAddress: String,

    /** 발신자 표시 이름 (예: "Error Archive"). */
    @field:NotBlank
    val fromName: String,

    /** 메일 제목 prefix. 워크스페이스명이 본문에 별도로 들어가므로 짧게. */
    val subjectPrefix: String = "[Error Archive]"
)

@Configuration
@EnableAsync   // 초대 메일을 커밋 후 별도 스레드에서 발송 (WorkspaceInvitationEmailListener)
@EnableConfigurationProperties(InvitationEmailProperties::class)
class InvitationEmailConfigRegistration
