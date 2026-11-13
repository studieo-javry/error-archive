package org.studieojavry.coreapi.agentcapture.application.port

import org.studieojavry.coreapi.agentcapture.domain.model.AgentCapture
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureArtifact
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureAttempt
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEvent
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureEventStatus
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureImport
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSession
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureSolutionCandidate
import org.studieojavry.coreapi.agentcapture.domain.model.ProjectBinding
import org.studieojavry.coreapi.agentcapture.domain.model.CaptureStatus
import java.time.Instant
import java.util.UUID

interface AgentCaptureRepositoryPort {
    fun save(capture: AgentCapture): AgentCapture
    fun findByUuid(captureUuid: UUID): AgentCapture?
    fun findByUuidAndOwner(captureUuid: UUID, ownerUserId: Long): AgentCapture?
    /** 소유자의 특정 상태 초안 목록(검토 큐 = READY_FOR_REVIEW). 최근 갱신순. */
    fun listByOwnerAndStatus(ownerUserId: Long, status: CaptureStatus): List<AgentCapture>
    /** 연결(install)이 생성한 특정 상태 초안 목록. 최근 갱신순. 진행 중(OPEN) Capture 재사용 후보 조회용. */
    fun listByConnectionAndStatus(connectionId: UUID, status: CaptureStatus): List<AgentCapture>
    /** 소유자의 since 이후 생성 초안을 상태별로 집계(자동기록 계측 — 오생성/채택 퍼널). 상태→건수. */
    fun countByOwnerSinceByStatus(ownerUserId: Long, since: Instant): Map<CaptureStatus, Long>
    /** 변이 연산 전용 — capture 행을 잠가(SELECT … FOR UPDATE) complete 와 쓰기를 직렬화한다. */
    fun findByUuidAndOwnerForUpdate(captureUuid: UUID, ownerUserId: Long): AgentCapture?
}

/** 검토 반영 기록(Capture 당 1건). 재반영 멱등·충돌 판정의 기준. */
interface CaptureImportRepositoryPort {
    fun save(record: CaptureImport): CaptureImport
    fun findByCaptureId(captureId: Long): CaptureImport?
}

/** Hook 자동수집 이벤트 인박스(P1). (connectionId, clientEventId) 로 멱등. */
interface CaptureEventRepositoryPort {
    fun findByConnectionAndClientId(connectionId: UUID, clientEventId: String): CaptureEvent?
    fun save(event: CaptureEvent): CaptureEvent

    /**
     * 본인 소유 이벤트를 상태별 조회(선택적 sessionId 필터). AI 가 어떤 이벤트를 Capture 에 연결할지
     * 판단하기 위한 후보 조회(B). observedAt DESC. 인덱스 ix_capture_event_owner_status 활용.
     */
    fun findByOwnerAndStatus(
        ownerUserId: Long,
        status: CaptureEventStatus,
        sessionId: String?,
        limit: Int,
    ): List<CaptureEvent>
}

/** 프로젝트 바인딩(P1 토대). 소유자별 projectKey 유일. */
interface ProjectBindingRepositoryPort {
    fun save(binding: ProjectBinding): ProjectBinding
    fun findById(id: Long): ProjectBinding?
    fun findByOwnerAndKey(ownerUserId: Long, projectKey: String): ProjectBinding?
    fun listByOwner(ownerUserId: Long): List<ProjectBinding>
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

interface CaptureArtifactRepositoryPort {
    fun save(artifact: CaptureArtifact): CaptureArtifact
    fun findByCaptureAndClientId(captureId: Long, clientArtifactId: String): CaptureArtifact?
    fun listByCapture(captureId: Long): List<CaptureArtifact>
    fun countByCapture(captureId: Long): Int
    fun totalSizeByCapture(captureId: Long): Long
    /** 근거 준비 상태 검사용 — COMPLETED/LINKED 인 clientArtifactId 집합. */
    fun completedClientIds(captureId: Long): Set<String>
}

interface CaptureSessionRepositoryPort {
    fun save(session: CaptureSession): CaptureSession
    fun findByUuid(sessionUuid: UUID): CaptureSession?
    /** Capture↔Session 연결(멱등 — 이미 연결돼 있으면 무시). */
    fun link(sessionId: Long, captureId: Long)
    /** 이 세션에 연결된 Capture id 집합(진행 중 후보를 세션 범위로 좁힐 때). */
    fun linkedCaptureIds(sessionId: Long): Set<Long>
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
    /** 보존 기간 지난 영수증 정리(벌크 삭제). 반환=삭제 건수. 엔티티 원본·CaptureImport 매핑은 건드리지 않는다. */
    fun deleteOlderThan(threshold: Instant): Int
}
