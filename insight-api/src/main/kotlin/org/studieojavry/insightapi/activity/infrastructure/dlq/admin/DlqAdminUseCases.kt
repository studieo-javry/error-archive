package org.studieojavry.insightapi.activity.infrastructure.dlq.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.insightapi.activity.infrastructure.dlq.ActivityEventDlqEntity
import org.studieojavry.insightapi.activity.infrastructure.dlq.ActivityEventDlqRepository
import org.studieojavry.insightapi.activity.infrastructure.dlq.DlqFailureKind
import org.studieojavry.insightapi.activity.infrastructure.dlq.DlqStatus
import java.time.Instant

/**
 * DLQ 운영 액션 — `/internal/admin/dlq/...` 의 application logic.
 *
 * 운영 흐름:
 *  1. Alert (`insight.dlq.dead.count > 0`) → list 로 DEAD row 조사
 *  2. 원인 분석 (rawPayload + lastError) → retry 또는 delete
 *  3. PARSE DEAD 는 보통 *코드 수정 후* retry (consumer 가 같은 payload 를 정상 처리하도록)
 *  4. INGEST DEAD 는 *broker/DB 복구 확인 후* retry
 */
@Service
class DlqAdminUseCases(
    private val dlqRepo: ActivityEventDlqRepository,
) {
    private val log = KotlinLogging.logger {}

    fun list(status: DlqStatus?, kind: DlqFailureKind?, limit: Int): List<ActivityEventDlqEntity> =
        dlqRepo.findRecent(status?.name, kind?.name, limit.coerceIn(1, 500))

    /**
     * DEAD 또는 정체된 PENDING 을 즉시 재시도 대기열로. attempts=0 으로 리셋.
     * RESOLVED 면 거부 — 이미 처리된 메시지를 재발송하면 *idempotency UNIQUE 충돌* 로 어차피 silent skip
     * 되지만, *의도된 운영 액션 아니라 사용자 실수* 일 가능성이 더 큼.
     */
    @Transactional
    fun retry(id: Long): ActivityEventDlqEntity {
        val row = dlqRepo.findById(id).orElseThrow {
            NoSuchElementException("DLQ row not found: $id")
        }
        if (row.status == DlqStatus.RESOLVED) {
            throw IllegalStateException("cannot retry RESOLVED row: id=$id")
        }
        // PARSE DEAD 도 retry 허용 — 코드 수정 후 같은 payload 가 통과하는 경우
        row.status = DlqStatus.PENDING
        row.attempts = 0
        row.lastError = "manually retried"
        row.nextRetryAt = Instant.now()
        // INGEST retry 는 DlqRetryJob 이 자동 처리.
        // PARSE retry 는 *Job 이 INGEST 만 pickup* 하므로 강제로 INGEST 로 marking (consumer 가 처리 안 함, Job 이 처리).
        if (row.failureKind == DlqFailureKind.PARSE) {
            row.failureKind = DlqFailureKind.INGEST
        }
        log.info { "DLQ admin: retry id=$id (was ${row.status}/${row.failureKind})" }
        return dlqRepo.save(row)
    }

    @Transactional
    fun delete(id: Long) {
        if (!dlqRepo.existsById(id)) {
            throw NoSuchElementException("DLQ row not found: $id")
        }
        dlqRepo.deleteById(id)
        log.info { "DLQ admin: delete id=$id" }
    }
}
