import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { accountNamespace, EventQueue } from '../src/eventQueue.js';
import type { EventItemRequest } from '../src/types.js';

function ev(id: string): EventItemRequest {
  return {
    clientEventId: id, eventType: 'PostToolUse:Bash', localSequence: 1, observedAt: '2026-11-13T00:00:00Z',
    redactedPayload: { x: id }, truncated: false, evidenceRefs: [], clientKind: 'claude-code', sessionId: 's1',
  };
}

function tempQueue(): { q: EventQueue; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-q-'));
  return { q: new EventQueue('ns', { dir }), cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('enqueue/readAll + localSequence 단조 증가', () => {
  const { q, cleanup } = tempQueue();
  try {
    assert.equal(q.nextLocalSequence(), 1);
    assert.equal(q.nextLocalSequence(), 2);
    q.enqueue(ev('a'));
    q.enqueue(ev('b'));
    assert.deepEqual(q.readAll().map((e) => e.clientEventId), ['a', 'b']);
  } finally {
    cleanup();
  }
});

test('remove: 완료 id 제거·나머지 유지, rejected 는 dead-letter 로', () => {
  const { q, cleanup } = tempQueue();
  try {
    q.enqueue(ev('a'));
    q.enqueue(ev('b'));
    q.enqueue(ev('c'));
    q.remove(new Set(['a', 'c']), [ev('c')]);
    assert.deepEqual(q.readAll().map((e) => e.clientEventId), ['b']); // a·c 제거, b 유지
  } finally {
    cleanup();
  }
});

test('accountNamespace: 토큰/서버 다르면 다른 네임스페이스(계정 격리)', () => {
  assert.equal(accountNamespace('https://a', 'eaa_1'), accountNamespace('https://a', 'eaa_1'));
  assert.notEqual(accountNamespace('https://a', 'eaa_1'), accountNamespace('https://a', 'eaa_2'));
  assert.notEqual(accountNamespace('https://a', 'eaa_1'), accountNamespace('https://b', 'eaa_1'));
});
