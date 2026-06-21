package org.studieojavry.coreapi.errorcase.attachment.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * presigned URL 만료(TTL) — 케이스 가시성별로 다르게. PUBLIC 은 오래, WORKSPACE/PRIVATE 은 짧게.
 * 짧을수록 URL 유출 위험 창이 작지만, 사용자가 상세 페이지를 오래 열어두면 만료됨(FE 재발급 필요).
 */
@ConfigurationProperties(prefix = "core.attachment.presign")
data class PresignTtlProperties(
    val publicTtl: Duration = Duration.ofMinutes(15),
    val restrictedTtl: Duration = Duration.ofMinutes(5),
)
