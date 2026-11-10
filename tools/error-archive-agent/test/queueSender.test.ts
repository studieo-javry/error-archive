import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiError, type ApiClient } from '../src/apiClient.js';
import { QueueSender, classify, type BackoffPolicy } from '../src/queue/sender.js';
import { QueueStore } from '../src/queue/store.js';
import type { EventResultType } from '../src/types.js';

const CONN = '22222222-2222-4222-8222-222222222222';
const FIXED_BACKOFF: BackoffPolicy = { baseMs: 1000, capMs: 60_000, authWaitMs: 30_000, jitter: () => 1 };

/** 필요한 메서드만 구현한 가짜 ApiClient. 호출을 기록하고, 지정 동작(throw/결과)을 수행. */
function fakeApi(overrides: Partial<ApiClient> = {}): { api: ApiClient; calls: string[] } {
  const calls: string[] = [];
  const base: Partial<ApiClient> = {
    registerSession: async () => { calls.push('registerSession'); return {} as never; },
    createCapture: async () => { calls.push('createCapture'); return {} as never; },
    addAttempt: async () => { calls.push('addAttempt'); return {} as never; },
    addSolution: async () => { calls.push('addSolution'); return {} as never; },
    completeCapture: async () => { calls.push('completeCapture'); return {} as never; },
    uploadArtifact: async () => { calls.push('uploadArtifact'); return {} as never; },
    ingestEvents: async () => { calls.push('ingestEvents'); return { results: [{ clientEventId: 'e', result: 'ACCEPTED' as EventResultType }] }; },
    linkEvents: async () => { calls.push('linkEvents'); return { linked: [], skipped: [] }; },
  };
  return { api: { ...base, ...overrides } as ApiClient, calls };
}

// ── classify 단위 ──────────────────────────────────────────────────────────────

test('classify: 네트워크(0)/5xx/429 는 재시도', () => {
  for (const status of [0, 500, 502, 429]) {
    const c = classify(new ApiError(status, undefined, 'x'), 0, FIXED_BACKOFF);
    assert.equal(c.retry, true);
  }
});

test('classify: 401/403 은 재시도하되 authWait 이상 대기', () => {
  for (const status of [401, 403]) {
    const c = classify(new ApiError(status, undefined, 'x'), 0, FIXED_BACKOFF);
    assert.equal(c.retry, true);
    assert.ok((c as { delayMs: number }).delayMs >= FIXED_BACKOFF.authWaitMs);
  }
});

test('classify: 400/409/413/422 는 영구 오류', () => {
  for (const status of [400, 409, 413, 422]) {
    const c = classify(new ApiError(status, 'CODE', 'x'), 0, FIXED_BACKOFF);
    assert.equal(c.retry, false);
  }
});

test('classify: 지수 backoff(attempts 증가로 지연 증가, cap 적용)', () => {
  const a0 = classify(new ApiError(500, undefined, 'x'), 0, FIXED_BACKOFF) as { delayMs: number };
  const a3 = classify(new ApiError(500, undefined, 'x'), 3, FIXED_BACKOFF) as { delayMs: number };
  assert.equal(a0.delayMs, 1000); // base*2^0, jitter=1
  assert.equal(a3.delayMs, 8000); // base*2^3
  const a99 = classify(new ApiError(500, undefined, 'x'), 99, FIXED_BACKOFF) as { delayMs: number };
  assert.equal(a99.delayMs, 60_000); // cap
});

// ── drain 통합(인메모리 큐 + 가짜 api) ──────────────────────────────────────────

function enqueueCreate(q: QueueStore, now?: number) {
  return q.enqueue({ connectionId: CONN, op: 'CREATE_CAPTURE', captureUuid: 'c1', idempotencyKey: 'k1', payload: { captureUuid: 'c1' } }, now);
}

test('drain: 의존 순서대로 전송하고 SENT 로 표시', async () => {
  const q = new QueueStore(':memory:');
  const create = enqueueCreate(q);
  const complete = q.enqueue({ connectionId: CONN, op: 'COMPLETE_CAPTURE', captureUuid: 'c1', idempotencyKey: 'k2', payload: {}, dependsOn: [create.id] });
  const { api, calls } = fakeApi();
  const sender = new QueueSender(q, api, { backoff: FIXED_BACKOFF });

  const r = await sender.drain();
  assert.equal(r.sent, 2);
  assert.deepEqual(calls, ['createCapture', 'completeCapture']); // create 먼저
  assert.equal(q.get(create.id)!.status, 'SENT');
  assert.equal(q.get(complete.id)!.status, 'SENT');
  q.close();
});

test('drain: 네트워크 오류는 재시도 예약(SENT 아님, attempts++)', async () => {
  const q = new QueueStore(':memory:');
  const now = 10_000;
  const create = enqueueCreate(q, now);
  const { api } = fakeApi({ createCapture: async () => { throw new ApiError(0, 'NETWORK', 'down'); } });
  const sender = new QueueSender(q, api, { backoff: FIXED_BACKOFF, now: () => now });

  const r = await sender.drain();
  assert.equal(r.retried, 1);
  assert.equal(r.sent, 0);
  const after = q.get(create.id)!;
  assert.equal(after.status, 'PENDING');
  assert.equal(after.attempts, 1);
  assert.equal(after.nextAttemptAt, now + 1000); // base*2^0, jitter=1
  q.close();
});

test('drain: 영구 오류(409)는 NEEDS_ATTENTION + 후행 BLOCKED', async () => {
  const q = new QueueStore(':memory:');
  const create = enqueueCreate(q);
  const complete = q.enqueue({ connectionId: CONN, op: 'COMPLETE_CAPTURE', captureUuid: 'c1', payload: {}, dependsOn: [create.id] });
  const { api, calls } = fakeApi({ createCapture: async () => { calls.push('createCapture'); throw new ApiError(409, 'CAPTURE_SEALED', 'sealed'); } });
  const sender = new QueueSender(q, api, { backoff: FIXED_BACKOFF });

  const r = await sender.drain();
  assert.equal(r.failed, 1);
  assert.equal(q.get(create.id)!.status, 'NEEDS_ATTENTION');
  assert.equal(q.get(complete.id)!.status, 'BLOCKED');
  assert.deepEqual(calls, ['createCapture']); // complete 는 전송 시도조차 안 함
  q.close();
});

test('drain: 이벤트 배치 HTTP 200 이어도 REJECTED 는 영구 실패', async () => {
  const q = new QueueStore(':memory:');
  const ev = q.enqueue({ connectionId: CONN, op: 'INGEST_EVENT', payload: { clientEventId: 'e1' } });
  const { api } = fakeApi({
    ingestEvents: async () => ({ results: [{ clientEventId: 'e1', result: 'REJECTED' as EventResultType, reason: 'payload too large' }] }),
  });
  const sender = new QueueSender(q, api, { backoff: FIXED_BACKOFF });

  const r = await sender.drain();
  assert.equal(r.failed, 1);
  const after = q.get(ev.id)!;
  assert.equal(after.status, 'NEEDS_ATTENTION');
  assert.match(after.lastError ?? '', /REJECTED/);
  q.close();
});

test('drain: 이벤트 DUPLICATE 는 성공(SENT)', async () => {
  const q = new QueueStore(':memory:');
  const ev = q.enqueue({ connectionId: CONN, op: 'INGEST_EVENT', payload: { clientEventId: 'e1' } });
  const { api } = fakeApi({
    ingestEvents: async () => ({ results: [{ clientEventId: 'e1', result: 'DUPLICATE' as EventResultType }] }),
  });
  const r = await new QueueSender(q, api, { backoff: FIXED_BACKOFF }).drain();
  assert.equal(r.sent, 1);
  assert.equal(q.get(ev.id)!.status, 'SENT');
  q.close();
});
