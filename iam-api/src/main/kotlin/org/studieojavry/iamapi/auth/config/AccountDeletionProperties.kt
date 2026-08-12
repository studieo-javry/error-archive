package org.studieojavry.iamapi.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 회원 탈퇴 grace period / finalize 배치 튜닝.
 * core-api 의 `core.attachment.orphan-*` 와 동일한 패턴.
 */
@ConfigurationProperties(prefix = "iam.account-deletion")
data class AccountDeletionProperties(
    /** PENDING_DELETION → DELETED 까지의 grace 기간. 기본 30일. */
    val gracePeriod: Duration = Duration.ofDays(30),

    /** finalize 배치 1회당 처리 건수. */
    val batchSize: Int = 200,
)