import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
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

function tempQueue(): { q: EventQueue; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-snd-'));
  return { q: new EventQueue('ns', { dir }), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
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
  const { q, cleanup } = tempQueue();
  try {
    q.enqueue(ev('bad'));
    const client = clientWith(async (req) => ({
      results: req.events.map((e) => ({ clientEventId: e.clientEventId, result: 'REJECTED', reason: 'too big' })),
    }));
    const r = await flushQueue(client, q);
    assert.equal(r.rejected, 1);
    assert.equal(q.size(), 0);
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
