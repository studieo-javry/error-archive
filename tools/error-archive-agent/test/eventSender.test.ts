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
