package org.studieojavry.notiapi.notification.infrastructure.dlq.admin

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.notiapi.notification.infrastructure.dlq.DlqFailureKind
import org.studieojavry.notiapi.notification.infrastructure.dlq.DlqStatus
import org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationEventDlqEntity
import org.studieojavry.notiapi.notification.infrastructure.dlq.NotificationEventDlqRepository
import java.time.Instant

/**
 * DLQ 운영 액션 — `/internal/admin/dlq/...` 의 application logic.
 *
 * 운영 흐름:
 *  1. Alert (`noti.dlq.dead.count > 0`) → list 로 DEAD row 조사.
 *  2. 원인 분석(rawPayload + lastError) → retry 또는 delete.
 *  3. PARSE DEAD 는 보통 *코드 수정 후* retry(consumer 가 같은 payload 를 정상 처리하도록).
 *  4. INGEST DEAD 는 *broker/DB 복구 확인 후* retry.
 */
@Service
class NotificationDlqAdminUseCases(
    private val dlqRepo: NotificationEventDlqRepository,
) {
    private val log = KotlinLogging.logger {}

    fun list(status: DlqStatus?, kind: DlqFailureKind?, limit: Int): List<NotificationEventDlqEntity> =
        dlqRepo.findRecent(status?.name, kind?.name, limit.coerceIn(1, 500))

    /** DEAD/정체 PENDING → PENDING + attempts=0. RESOLVED 는 거부. PARSE 는 INGEST 로 승격(retry 잡이 pickup). */
    @Transactional
    fun retry(id: Long): NotificationEventDlqEntity {
        val row = dlqRepo.findById(id).orElseThrow { NoSuchElementException("DLQ row not found: $id") }
        if (row.status == DlqStatus.RESOLVED) {
            throw IllegalStateException("cannot retry RESOLVED row: id=$id")
        }
        row.status = DlqStatus.PENDING
        row.attempts = 0
        row.lastError = "manually retried"
        row.nextRetryAt = Instant.now()
        if (row.failureKind == DlqFailureKind.PARSE) {
            row.failureKind = DlqFailureKind.INGEST // retry 잡은 INGEST 만 pickup
        }
        log.info { "[noti-dlq] admin retry id=$id (was ${row.status}/${row.failureKind})" }
        return dlqRepo.save(row)
    }

    @Transactional
    fun delete(id: Long) {
        if (!dlqRepo.existsById(id)) throw NoSuchElementException("DLQ row not found: $id")
        dlqRepo.deleteById(id)
        log.info { "[noti-dlq] admin delete id=$id" }
    }
}
