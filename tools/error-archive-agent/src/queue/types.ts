/**
 * 로컬 오프라인 큐(SQLite)의 레코드 계약. 큐는 서버로 보낼 **API 연산의 아웃박스**다 —
 * Hook 이벤트뿐 아니라 session/capture 생성·근거 업로드·attempt/solution·complete 가 한 줄씩 들어간다.
 * 각 항목은 재시도에서도 **같은 식별자**(captureUuid/clientAttemptId/clientEventId/Idempotency-Key)를
 * 유지해, 서버의 멱등·UNIQUE 제약이 중복을 흡수한다(네트워크 재시도·응답 유실이 중복 생성으로 번지지 않음).
 */

/** 큐 항목이 나타내는 서버 연산. 의존 순서(session/capture → 업로드 → attempt/solution → complete)의 노드이기도. */
export type QueueOp =
  | 'REGISTER_SESSION'
  | 'CREATE_CAPTURE'
  | 'UPLOAD_ARTIFACT'
  | 'ADD_ATTEMPT'
  | 'ADD_SOLUTION'
  | 'COMPLETE_CAPTURE'
  | 'INGEST_EVENT'
  | 'LINK_EVENTS';

/**
 * 항목 상태.
 *  - PENDING        전송 대기(next_attempt_at 이후, 선행 의존이 모두 SENT 면 claim 가능)
 *  - INFLIGHT       한 sender 가 lease 를 쥐고 전송 중
 *  - SENT           서버 ACK 완료(24h 후 payload 로컬 제거)
 *  - BLOCKED        선행 의존이 영구 실패 → 보낼 수 없음(complete 선전송 금지)
 *  - NEEDS_ATTENTION 7일 자동재시도 소진 또는 영구오류 — **조용히 폐기하지 않고** 사람이 볼 수 있게 남긴다
 */
export type QueueStatus = 'PENDING' | 'INFLIGHT' | 'SENT' | 'BLOCKED' | 'NEEDS_ATTENTION';

/** 큐에 넣을 새 항목(id·상태·재시도 메타는 스토어가 채운다). */
export interface NewQueueItem {
  connectionId: string;
  op: QueueOp;
  /** 대상 초안(없는 연산=세션/이벤트 적재는 null). */
  captureUuid?: string | null;
  projectBindingId?: number | null;
  /** 생성/봉인 계열의 Idempotency-Key(재시도에서 동일). 이벤트/링크는 payload 내부 식별자로 멱등. */
  idempotencyKey?: string | null;
  /** 서버 요청 본문(JSON 직렬화 가능). */
  payload: unknown;
  /** 선행해야 하는 다른 큐 항목 id 목록(모두 SENT 여야 claim). */
  dependsOn?: number[];
}

/** 저장된 큐 항목(스토어가 돌려주는 전체 레코드). */
export interface QueueItem {
  id: number;
  connectionId: string;
  op: QueueOp;
  captureUuid: string | null;
  projectBindingId: number | null;
  idempotencyKey: string | null;
  payload: unknown;
  payloadHash: string;
  dependsOn: number[];
  status: QueueStatus;
  attempts: number;
  nextAttemptAt: number;
  lastError: string | null;
  enqueuedAt: number;
  sentAt: number | null;
  sizeBytes: number;
}

/** 큐가 256 MiB 상한을 넘겨 **수집을 중단**해야 할 때(조용한 폐기 금지 — 호출 측이 표시). */
export class QueueFullError extends Error {
  constructor(
    readonly currentBytes: number,
    readonly capBytes: number,
  ) {
    super(`로컬 큐가 상한(${capBytes} bytes)에 도달해 수집을 멈춥니다(현재 ${currentBytes} bytes). 네트워크 복구 후 비워집니다.`);
    this.name = 'QueueFullError';
  }
}
