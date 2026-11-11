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
