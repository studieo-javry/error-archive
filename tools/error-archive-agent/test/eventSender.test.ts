import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { ApiClient } from '../src/apiClient.js';
import { EventQueue } from '../src/eventQueue.js';
import { flushQueue } from '../src/eventSender.js';
import type { EventBatchRequest, EventBatchResponse, EventItemRequest } from '../src/types.js';

function ev(id: string): EventItemRequest {
  return {
    clientEventId: id, eventType: 'PostToolUse', localSequence: 1, observedAt: '2026-11-13T00:00:00Z',
    redactedPayload: {}, truncated: false, evidenceRefs: [], sessionId: 's1',
  };
}

function tempQueue(): { q: EventQueue; dir: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-snd-'));
  return { q: new EventQueue('ns', { dir }), dir, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

function clientWith(fn: (req: EventBatchRequest) => Promise<EventBatchResponse>): ApiClient {
  return { ingestEvents: fn } as unknown as ApiClient;
}

test('flush: ACCEPTED/DUPLICATE 는 큐에서 제거', async () => {
  const { q, cleanup } = tempQueue();
  try {
    q.enqueue(ev('a'));
    q.enqueue(ev('b'));
    const client = clientWith(async (req) => ({
      results: req.events.map((e, i) => ({ clientEventId: e.clientEventId, result: i === 0 ? 'ACCEPTED' : 'DUPLICATE' })),
    }));
    const r = await flushQueue(client, q);
    assert.equal(r.sent, 2);
    assert.equal(r.pending, 0);
    assert.equal(q.size(), 0);
  } finally {
    cleanup();
  }
});

test('flush: REJECTED 는 제거+dead-letter, 큐에서 사라짐', async () => {
  const { q, dir, cleanup } = tempQueue();
  try {
    q.enqueue(ev('bad'));
    const client = clientWith(async (req) => ({
      results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'REJECTED', reason: 'too big' })),
    }));
    const r = await flushQueue(client, q);
    assert.equal(r.rejected, 1);
    assert.equal(q.size(), 0); // pending 에서 제거
    // dead-letter 로 **실제 이동**됐는지(유실 아님). shard = 'ba'(clientEventId 'bad' 앞 2자리)
    assert.ok(existsSync(join(dir, 'ns', 'rejected', 'ba', 'bad.json')), 'rejected 로 원자적 이동됨');
  } finally {
    cleanup();
  }
});

test('flush: 네트워크 오류면 pending 유지(오프라인 재시도)', async () => {
  const { q, cleanup } = tempQueue();
  try {
    q.enqueue(ev('a'));
    const client = clientWith(async () => {
      throw new Error('network down');
    });
    const r = await flushQueue(client, q);
    assert.equal(r.sent, 0);
    assert.equal(q.size(), 1); // 잔류
  } finally {
    cleanup();
  }
});

// ── Phase 3: 전송 직전 승인 재검사 + _local strip ────────────────────────────
import { setConsent } from '../src/consentIndex.js';
import type { QueuedEvent } from '../src/types.js';

const CNS = 'cns';
function evLocal(id: string, gitRoot: string, remote: string | null): QueuedEvent {
  return { ...ev(id), _local: { gitRoot, remote, projectKey: remote ?? gitRoot } };
}
function tmpConsent(): { path: string; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-snd-consent-'));
  return { path: join(dir, 'c.json'), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('flush(Phase3): 승인된 _local 은 전송하고 payload 에서 _local 을 제거(strip)', async () => {
  const { q, cleanup } = tempQueue();
  const consent = tmpConsent();
  try {
    setConsent(CNS, { projectKey: 'r1', gitRoot: '/dev/a', status: 'APPROVED', remoteAtApproval: 'r1' }, { path: consent.path });
    q.enqueue(evLocal('a', '/dev/a', 'r1'));
    let seen: unknown;
    const client = clientWith(async (req) => {
      seen = req.events[0];
      return { results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'ACCEPTED' })) };
    });
    const r = await flushQueue(client, q, { consentNamespace: CNS, consentPath: consent.path });
    assert.equal(r.sent, 1);
    assert.equal(r.dropped, 0);
    assert.equal((seen as Record<string, unknown>)._local, undefined, '_local 은 서버로 보내지 않음');
  } finally {
    consent.cleanup();
    cleanup();
  }
});

test('flush(Phase3): 미승인 _local 은 전송 않고 드랍(해제 후 잔여)', async () => {
  const { q, cleanup } = tempQueue();
  const consent = tmpConsent();
  try {
    // 동의 인덱스에 레코드 없음 → 미승인.
    q.enqueue(evLocal('a', '/dev/a', 'r1'));
    let called = false;
    const client = clientWith(async (req) => {
      called = true;
      return { results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'ACCEPTED' })) };
    });
    const r = await flushQueue(client, q, { consentNamespace: CNS, consentPath: consent.path });
    assert.equal(r.sent, 0);
    assert.equal(r.dropped, 1);
    assert.equal(called, false, '미승인이면 서버 전송 자체를 안 함');
    assert.equal(q.size(), 0, '드랍되어 큐에서 제거');
  } finally {
    consent.cleanup();
    cleanup();
  }
});

test('flush(Phase3): _local 없는 레거시 이벤트는 그대로 전송', async () => {
  const { q, cleanup } = tempQueue();
  const consent = tmpConsent();
  try {
    q.enqueue(ev('legacy'));
    const client = clientWith(async (req) => ({ results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'ACCEPTED' })) }));
    const r = await flushQueue(client, q, { consentNamespace: CNS, consentPath: consent.path });
    assert.equal(r.sent, 1);
    assert.equal(r.dropped, 0);
  } finally {
    consent.cleanup();
    cleanup();
  }
});

// ── PR-4: 조회직전 flush 예산(maxEvents) + 최신 우선(newestFirst) ──────────────
function evAt(id: string, observedAt: string): EventItemRequest {
  return { ...ev(id), observedAt };
}

test('(PR-4) maxEvents: 예산만큼만 전송하고 초과분은 pending 유지', async () => {
  const { q, cleanup } = tempQueue();
  try {
    for (let i = 0; i < 250; i++) q.enqueue(ev(`e${String(i).padStart(3, '0')}`));
    let received = 0;
    const client = clientWith(async (req) => {
      received += req.events.length;
      return { results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'ACCEPTED' })) };
    });
    const r = await flushQueue(client, q, { maxEvents: 100 });
    assert.equal(received, 100, '예산(100)만 전송 시도');
    assert.equal(r.sent, 100);
    assert.equal(r.pending, 150, '초과분 150 은 pending 유지(백그라운드가 처리)');
    assert.equal(q.size(), 150);
  } finally {
    cleanup();
  }
});

test('(PR-4) newestFirst: 최신 이벤트부터 전송(조회에 바로 보이게)', async () => {
  const { q, cleanup } = tempQueue();
  try {
    q.enqueue(evAt('old', '2026-11-10T00:00:01Z'));
    q.enqueue(evAt('mid', '2026-11-10T00:00:02Z'));
    q.enqueue(evAt('new', '2026-11-10T00:00:03Z'));
    const sentIds: string[] = [];
    const client = clientWith(async (req) => {
      for (const e of req.events) sentIds.push(e.clientEventId);
      return { results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'ACCEPTED' })) };
    });
    // 예산 1 + 최신우선 → 가장 최신('new')만 전송, 과거 둘은 pending 유지
    const r = await flushQueue(client, q, { maxEvents: 1, newestFirst: true });
    assert.deepEqual(sentIds, ['new']);
    assert.equal(r.sent, 1);
    assert.equal(q.size(), 2);
  } finally {
    cleanup();
  }
});

test('(PR-4) 두 flush 동시 실행 — per-file + 멱등으로 유실·크래시 없음(분리 single-flight 트레이드오프)', async () => {
  const { q, cleanup } = tempQueue();
  try {
    for (let i = 0; i < 50; i++) q.enqueue(ev(`c${String(i).padStart(3, '0')}`));
    const seen = new Set<string>();
    const client = clientWith(async (req) => {
      await new Promise((r) => setTimeout(r, 1)); // 두 flush 의 실제 겹침 재현
      for (const e of req.events) seen.add(e.clientEventId);
      return { results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'ACCEPTED' })) };
    });
    // 주기 flush 와 조회직전 flush 가 분리된 뒤엔 둘이 동시에 같은 큐를 비울 수 있다.
    const [a, b] = await Promise.all([flushQueue(client, q), flushQueue(client, q)]);
    assert.equal(q.size(), 0, '동시 flush 후 큐 완전히 비움(유실 없음)');
    assert.equal(seen.size, 50, '모든 이벤트가 최소 한 번은 서버로 전송됨');
    assert.ok(a.sent + b.sent >= 50, '중복 전송은 허용(멱등) — 합계 ≥ 전체'); // 트레이드오프: 중복 가능
  } finally {
    cleanup();
  }
});
