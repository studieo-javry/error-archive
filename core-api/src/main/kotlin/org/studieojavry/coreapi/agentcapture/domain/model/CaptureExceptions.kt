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
