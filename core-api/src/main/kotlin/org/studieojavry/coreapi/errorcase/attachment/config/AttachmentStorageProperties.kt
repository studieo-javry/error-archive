package org.studieojavry.coreapi.errorcase.attachment.config

import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

/**
 * orphan(미연결) 첨부 GC 설정. 스토리지 백엔드 설정은 [AttachmentS3Properties] 로 분리.
 */
@Validated
@ConfigurationProperties(prefix = "core.attachment")
data class AttachmentStorageProperties(
    /**
     * 미연결(errorCaseId IS NULL) 첨부를 이 시간 이상 지나면 GC 대상으로 본다.
     * 폼을 오래 작성하는 사용자가 첨부를 잃지 않게 넉넉히. ("24h", "48h" 등)
     */
    val orphanTtl: Duration = Duration.ofHours(24),

    /** GC 한 배치에서 처리할 최대 건수 (긴 트랜잭션/락 방지). */
    @field:Positive
    val orphanGcBatchSize: Int = 500,
)
