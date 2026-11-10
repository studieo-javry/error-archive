import assert from 'node:assert/strict';
import { test } from 'node:test';
import { QueueStore } from '../src/queue/store.js';
import type { NewQueueItem } from '../src/queue/types.js';
import { QueueFullError } from '../src/queue/types.js';

const CONN = '11111111-1111-4111-8111-111111111111';

function store(opts?: ConstructorParameters<typeof QueueStore>[1]): QueueStore {
  return new QueueStore(':memory:', opts);
}

function item(op: NewQueueItem['op'], payload: unknown, extra: Partial<NewQueueItem> = {}): NewQueueItem {
  return { connectionId: CONN, op, payload, ...extra };
}

test('enqueue → claim → markSent 기본 흐름', () => {
  const q = store();
  const it = q.enqueue(item('CREATE_CAPTURE', { captureUuid: 'c1', title: 'x' }));
  assert.equal(it.status, 'PENDING');
  assert.ok(it.sizeBytes > 0);

  const claimed = q.claim(1000);
  assert.ok(claimed);
  assert.equal(claimed!.id, it.id);
  assert.equal(claimed!.status, 'INFLIGHT');

  // lease 가 걸려 바로 다시 claim 되지 않는다(중복 전송 방지)
  assert.equal(q.claim(1000), null);

  q.markSent(claimed!.id);
  assert.equal(q.get(it.id)!.status, 'SENT');
  q.close();
});

test('의존 순서: 선행이 SENT 되기 전에는 후행이 claim 되지 않는다', () => {
  const q = store();
  const create = q.enqueue(item('CREATE_CAPTURE', { captureUuid: 'c1' }));
  const complete = q.enqueue(item('COMPLETE_CAPTURE', { captureUuid: 'c1' }, { dependsOn: [create.id], captureUuid: 'c1' }));

  // 가장 오래된 것부터 집으므로 create 가 먼저. complete 는 아직 못 집는다.
  const first = q.claim(1000)!;
  assert.equal(first.id, create.id);
  assert.equal(q.claim(1000), null); // complete 는 create 가 SENT 전이라 대기

  q.markSent(create.id);
  const second = q.claim(1000)!;
  assert.equal(second.id, complete.id);
  q.close();
});

test('선행 영구 실패 → 후행 BLOCKED(complete 선전송 금지)', () => {
  const q = store();
  const create = q.enqueue(item('CREATE_CAPTURE', { captureUuid: 'c1' }));
  const complete = q.enqueue(item('COMPLETE_CAPTURE', { captureUuid: 'c1' }, { dependsOn: [create.id] }));

  q.markPermanentFailure(create.id, '409 CONFLICT');
  assert.equal(q.get(create.id)!.status, 'NEEDS_ATTENTION');
  assert.equal(q.get(complete.id)!.status, 'BLOCKED');
  assert.equal(q.claim(1000), null); // 둘 다 claim 불가
  q.close();
});

test('markRetry: 7일 창 내면 PENDING 재예약, 창 밖이면 NEEDS_ATTENTION', () => {
  const q = store({ retryWindowMs: 1000 });
  const now = 10_000;
  const a = q.enqueue(item('INGEST_EVENT', { clientEventId: 'e1' }), now);

  // 창 내 재시도 → PENDING, next_attempt_at 미래
  const s1 = q.markRetry(a.id, now + 500, 'network', now + 100);
  assert.equal(s1, 'PENDING');
  assert.equal(q.get(a.id)!.attempts, 1);
  assert.equal(q.get(a.id)!.nextAttemptAt, now + 500);

  // enqueued_at(10_000)으로부터 1000ms 넘은 시점의 재시도 → NEEDS_ATTENTION
  const s2 = q.markRetry(a.id, now + 9999, 'network', now + 2000);
  assert.equal(s2, 'NEEDS_ATTENTION');
  assert.equal(q.get(a.id)!.status, 'NEEDS_ATTENTION');
  q.close();
});

test('backoff: next_attempt_at 미래면 claim 안 됨, 지나면 claim 됨', () => {
  const q = store();
  const a = q.enqueue(item('INGEST_EVENT', { clientEventId: 'e1' }), 1000);
  q.markRetry(a.id, 5000, 'network', 1100);
  assert.equal(q.claim(1000, 4999), null); // 아직 미래
  assert.ok(q.claim(1000, 5000)); // 도달
  q.close();
});

test('256MiB 상한 초과 시 QueueFullError(조용한 폐기 금지)', () => {
  const q = store({ capBytes: 200 });
  q.enqueue(item('INGEST_EVENT', { blob: 'x'.repeat(120) })); // payload > 100 bytes
  assert.throws(() => q.enqueue(item('INGEST_EVENT', { blob: 'y'.repeat(120) })), QueueFullError);
  q.close();
});

test('reclaimExpiredLeases: 크래시로 만료된 INFLIGHT 를 PENDING 복구', () => {
  const q = store();
  const a = q.enqueue(item('CREATE_CAPTURE', { captureUuid: 'c1' }), 1000);
  const claimed = q.claim(100, 1000)!; // lease until 1100
  assert.equal(claimed.status, 'INFLIGHT');
  assert.equal(q.reclaimExpiredLeases(1050), 0); // 아직 유효
  assert.equal(q.reclaimExpiredLeases(1200), 1); // 만료 → 복구
  assert.equal(q.get(a.id)!.status, 'PENDING');
  assert.ok(q.claim(1000, 1200)); // 다시 집을 수 있다
  q.close();
});

test('purgeSentPayloads: 24h 지난 SENT payload 만 비운다(행은 유지)', () => {
  const q = store({ sentPayloadTtlMs: 1000 });
  const a = q.enqueue(item('CREATE_CAPTURE', { captureUuid: 'c1' }), 1000);
  q.markSent(a.id, 1000);
  assert.equal(q.purgeSentPayloads(1500), 0); // 아직 24h(=1000ms) 전
  assert.equal(q.purgeSentPayloads(2001), 1); // 지남
  const after = q.get(a.id)!;
  assert.equal(after.status, 'SENT');
  assert.equal(after.payload, null);
  assert.equal(after.sizeBytes, 0);
  q.close();
});

test('정리된(행 없는) 의존은 SENT 로 간주 → 후행 claim 가능', () => {
  const q = store();
  // 존재하지 않는 선행 id 999 (이미 SENT 후 정리됐다고 가정)
  const complete = q.enqueue(item('COMPLETE_CAPTURE', { captureUuid: 'c1' }, { dependsOn: [999] }));
  const claimed = q.claim(1000);
  assert.ok(claimed);
  assert.equal(claimed!.id, complete.id);
  q.close();
});

test('counts/needsAttention: status 요약', () => {
  const q = store();
  const a = q.enqueue(item('INGEST_EVENT', { clientEventId: 'e1' }));
  q.enqueue(item('INGEST_EVENT', { clientEventId: 'e2' }));
  q.markPermanentFailure(a.id, 'boom');
  const c = q.counts();
  assert.equal(c.NEEDS_ATTENTION, 1);
  assert.equal(c.PENDING, 1);
  assert.equal(q.needsAttention().length, 1);
  assert.equal(q.needsAttention()[0].lastError, 'boom');
  q.close();
});
