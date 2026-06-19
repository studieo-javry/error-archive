package org.studieojavry.notiapi.notification.infrastructure.dlq

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.notiapi.notification.infrastructure.kafka.KafkaEventType
import tools.jackson.core.JacksonException

/**
 * consumer 가 처리 실패한 이벤트를 DLQ 테이블로 격리.
 *
 * 분류:
 *  - **PARSE**(JSON/스키마 오류 = [JacksonException] 계열) → 재시도 무의미 → status=DEAD 즉시.
 *  - **INGEST**(그 외 = DB lock / 순간 infra 오류) → status=PENDING → [NotificationDlqRetryJob] 이 재시도.
 *
 * 실패한 use case 의 트랜잭션과 별개인 **자체 트랜잭션**으로 DLQ row 를 저장한다(use case tx 는 이미 롤백됨).
 */
@Component
class NotificationDlqSink(
    private val dlqRepo: NotificationEventDlqRepository,
) {
    private val log = KotlinLogging.logger {}

    @Transactional
    fun record(type: KafkaEventType, rawPayload: String, ex: Throwable) {
        val parse = isParseFailure(ex)
        val entity = NotificationEventDlqEntity(
            eventType = type,
            rawPayload = rawPayload,
            failureKind = if (parse) DlqFailureKind.PARSE else DlqFailureKind.INGEST,
            lastError = (ex.message ?: ex.javaClass.simpleName).take(2000),
            status = if (parse) DlqStatus.DEAD else DlqStatus.PENDING,
        )
        dlqRepo.save(entity)
        if (parse) {
            log.warn(ex) { "[noti-dlq] PARSE→DEAD type=$type payload=${rawPayload.take(200)}" }
        } else {
            log.warn(ex) { "[noti-dlq] INGEST→PENDING type=$type (재시도 예약)" }
        }
    }

    /** cause 체인을 훑어 Jackson 파싱/역직렬화 예외가 있으면 PARSE 로 판정. */
    private fun isParseFailure(ex: Throwable): Boolean {
        var cur: Throwable? = ex
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur is JacksonException) return true
            cur = cur.cause
            depth++
        }
        return false
    }
}
