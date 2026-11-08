package org.studieojavry.coreapi.agentcapture.application.usecase

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceipt
import org.studieojavry.coreapi.agentcapture.application.port.IdempotencyReceiptPort
import org.studieojavry.coreapi.agentcapture.domain.model.IdempotencyConflictException
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest

/**
 * 생성·complete·import 요청의 멱등 처리. 범위 = scopeKey(connectionId) + operation + key(Idempotency-Key).
 *
 * 처리 순서(이슈 §5): (호출자가 인증·권한 확인) → 성공 이력 재생 → 없으면 block 실행 → 영수증 저장.
 *  - 같은 키·같은 payload(requestHash) → 저장된 응답(status+body)을 그대로 재생(최초 결과).
 *  - 같은 키·다른 payload → 409 IDEMPOTENCY_CONFLICT.
 *  - block 과 영수증 저장은 한 트랜잭션 — DB 커밋 전에는 성공을 남기지 않는다.
 *  - 동시 중복은 UNIQUE(scope,op,key) + 엔티티 UNIQUE(capture_uuid 등)로 방어. 영수증 저장이 경합에
 *    지면 예외가 전파되어 트랜잭션이 롤백되고, 재전송이 커밋된 영수증을 재생한다(at-least-once).
 */
@Service
class IdempotencyService(
    private val receipts: IdempotencyReceiptPort,
    private val objectMapper: ObjectMapper,
) {
    data class Result<T>(val status: Int, val body: T, val replayed: Boolean)

    @Transactional
    fun <T : Any> execute(
        scopeKey: String,
        operation: String,
        key: String,
        requestPayload: Any,
        responseType: Class<T>,
        successStatus: Int,
        block: () -> T,
    ): Result<T> {
        val requestHash = sha256(objectMapper.writeValueAsString(requestPayload))

        receipts.find(scopeKey, operation, key)?.let { existing ->
            if (existing.requestHash != requestHash) throw IdempotencyConflictException(key)
            val body = objectMapper.readValue(existing.responseBody, responseType)
            return Result(existing.responseStatus, body, replayed = true)
        }

        val body = block()
        val serialized = objectMapper.writeValueAsString(body)
        try {
            receipts.save(
                IdempotencyReceipt(
                    scopeKey = scopeKey,
                    operation = operation,
                    key = key,
                    requestHash = requestHash,
                    responseStatus = successStatus,
                    responseBody = serialized,
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            // 동시 요청이 같은 키를 먼저 커밋 — 롤백시켜 재전송이 재생하게 한다.
            throw ConcurrentRequestException(key, ex)
        }
        return Result(successStatus, body, replayed = false)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/** 같은 멱등 키의 동시 요청 경합 — 재시도하면 최초 결과가 재생된다(409 retryable). */
class ConcurrentRequestException(val key: String, cause: Throwable) :
    RuntimeException("concurrent request for idempotency key: $key", cause)
