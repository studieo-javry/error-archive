package org.studieojavry.coreapi.agentcapture.domain.model

import java.util.UUID

/** 종료/봉인된 Capture 에 에이전트가 쓰기를 시도 → 409 CAPTURE_SEALED. */
class CaptureSealedException(val captureUuid: UUID) :
    RuntimeException("capture is sealed (not OPEN): $captureUuid")

/** expectedRevision/If-Match 불일치 → 409 REVISION_CONFLICT. */
class RevisionConflictException(val captureUuid: UUID, val expected: Int, val actual: Int) :
    RuntimeException("revision conflict on $captureUuid: expected=$expected actual=$actual")

class CaptureNotFoundException(val captureUuid: UUID) :
    RuntimeException("capture not found: $captureUuid")

/** 같은 키·다른 payload → 409 IDEMPOTENCY_CONFLICT. */
class IdempotencyConflictException(val key: String) :
    RuntimeException("idempotency key reused with a different payload: $key")

/**
 * Solution 후보가 참조한 clientAttemptId 가 같은 Capture 에 존재하지 않음(또는 다른 Capture 의 것).
 * → 400/409. 다른 Capture 의 Attempt 와 중복 ID 를 거부한다.
 */
class UnknownAttemptReferenceException(val captureUuid: UUID, val clientAttemptId: String) :
    RuntimeException("solution references unknown attempt '$clientAttemptId' in $captureUuid")

/** Solution 후보는 최소 1개의 존재하는 Attempt 를 참조해야 함 → 422 SOLUTION_WITHOUT_ATTEMPT. */
class SolutionWithoutAttemptException(val captureUuid: UUID) :
    RuntimeException("solution for $captureUuid must reference at least one attempt")

/**
 * verification.result=PASSED 인데 근거(command·testId·evidence)가 하나도 없음 → 422 VERIFICATION_EVIDENCE_REQUIRED.
 * 근거 "존재" 만 확인한다(내용의 완전한 진실성은 서버가 보증하지 않음, 이슈 §3).
 */
class VerificationEvidenceRequiredException(val captureUuid: UUID) :
    RuntimeException("PASSED verification for $captureUuid requires command, testId, or evidence")

/** 허용되지 않은 첨부 형식(SVG/HTML/실행/압축 등) → 415. */
class UnsupportedArtifactException(message: String) : RuntimeException(message)

/** 파일 또는 Capture 총량 한도 초과 → 413. */
class ArtifactTooLargeException(message: String) : RuntimeException(message)

/** 같은 clientArtifactId 인데 다른 해시 → 409. */
class ArtifactHashMismatchException(val clientArtifactId: String) :
    RuntimeException("artifact '$clientArtifactId' already exists with a different hash")

/**
 * complete 시 참조한 근거(evidenceRef)가 아직 업로드되지 않았거나 누락 → 422 EVIDENCE_NOT_READY.
 * 사용자는 필요 없는 근거를 제외한 뒤 완료할 수 있다(웹 검토 경로, PR4).
 */
class EvidenceNotReadyException(val captureUuid: UUID, val missingRefs: List<String>) :
    RuntimeException("evidence not ready for $captureUuid: missing=$missingRefs")

/**
 * 반영(import)은 봉인된(READY_FOR_REVIEW) 초안에서만 가능 → 409 CAPTURE_NOT_REVIEWABLE.
 * OPEN(미봉인)·DISCARDED 에 import 를 시도하면 던진다. (IMPORTED 는 use case 가 멱등 반환으로 처리.)
 */
class CaptureNotReviewableException(val captureUuid: UUID, val status: CaptureStatus) :
    RuntimeException("capture $captureUuid is not reviewable (status=$status)")

/** 지정한 ProjectBinding 이 없거나 본인 소유가 아님 → 404(존재 노출 회피). */
class ProjectBindingNotFoundException(val projectBindingId: Long) :
    RuntimeException("project binding $projectBindingId not found")

/** 에이전트 case:read 대상이 아님(없거나·본인 소유 아님·바인딩 미귀속) → 404(존재 노출 회피). */
class AgentCaseNotFoundException(val caseId: Long) :
    RuntimeException("agent-readable case $caseId not found")

/**
 * 이미 반영된(IMPORTED) 초안에 대한 충돌 → 409 CAPTURE_ALREADY_IMPORTED.
 *  - 폐기 시도(caseId 불필요), 또는
 *  - **다른 반영 내용으로 재반영** 시도(이미 생성된 caseId 를 권한 범위 안에서 안내).
 */
class CaptureAlreadyImportedException(val captureUuid: UUID, val caseId: Long? = null) :
    RuntimeException("capture $captureUuid is already imported" + (caseId?.let { " as case $it" } ?: ""))
