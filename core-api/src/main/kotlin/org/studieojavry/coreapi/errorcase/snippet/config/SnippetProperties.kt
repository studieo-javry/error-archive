package org.studieojavry.coreapi.errorcase.snippet.config

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * 코드 스니펫 orphan 정리 정책 (첨부의 core.attachment.orphan-* 와 대칭).
 * 스니펫은 파일이 없어 DB row 만 정리하면 된다.
 */
@Validated
@ConfigurationProperties(prefix = "core.snippet")
data class SnippetProperties(
    /** 미연결 스니펫을 orphan 으로 보는 경과 시간. ("24h" 등) */
    val orphanTtl: Duration = Duration.ofHours(24),

    /** GC 한 배치 처리량. */
    @field:Positive
    val orphanGcBatchSize: Int = 500,
)
