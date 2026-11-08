package org.studieojavry.coreapi.agentcapture.application.port

import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import java.time.Instant
import java.util.UUID

interface AgentCaptureRepositoryPort {
    fun save(capture: AgentCapture): AgentCapture
    fun findByUuid(captureUuid: UUID): AgentCapture?
    fun findByUuidAndOwner(captureUuid: UUID, ownerUserId: Long): AgentCapture?
}

interface CaptureAttemptRepositoryPort {
    fun save(attempt: CaptureAttempt): CaptureAttempt
    fun findByCaptureAndClientId(captureId: Long, clientAttemptId: String): CaptureAttempt?
    fun listByCapture(captureId: Long): List<CaptureAttempt>
    fun countByCapture(captureId: Long): Int
    /** 같은 Capture 의 Attempt clientId 집합(Solution 참조 검증용). */
    fun clientAttemptIds(captureId: Long): Set<String>
}

interface CaptureSolutionRepositoryPort {
    fun save(solution: CaptureSolutionCandidate): CaptureSolutionCandidate
    fun findByCaptureAndClientId(captureId: Long, clientSolutionId: String): CaptureSolutionCandidate?
    fun listByCapture(captureId: Long): List<CaptureSolutionCandidate>
}

interface CaptureSessionRepositoryPort {
    fun save(session: CaptureSession): CaptureSession
    fun findByUuid(sessionUuid: UUID): CaptureSession?
    /** Capture↔Session 연결(멱등 — 이미 연결돼 있으면 무시). */
    fun link(sessionId: Long, captureId: Long)
}

/**
 * 요청 멱등 영수증. 범위 = (scopeKey=connectionId/userId) + operation + key.
 * 같은 키·같은 payload(requestHash) → 저장된 응답 재생, 다른 payload → 409.
 */
data class IdempotencyReceipt(
    val scopeKey: String,
    val operation: String,
    val key: String,
    val requestHash: String,
    val responseStatus: Int,
    val responseBody: String,
    val createdAt: Instant = Instant.now(),
)

interface IdempotencyReceiptPort {
    fun find(scopeKey: String, operation: String, key: String): IdempotencyReceipt?
    /** UNIQUE(scope_key, operation, key) 위반 시 예외를 던진다(동시 요청 → service 가 재조회). */
    fun save(receipt: IdempotencyReceipt): IdempotencyReceipt
}
