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

test('enqueue/readAll — 적재분 보존(observedAt·clientEventId 순)', () => {
  const { q, cleanup } = tempQueue();
  try {
    q.enqueue(ev('a'));
    q.enqueue(ev('b'));
    assert.deepEqual(q.readAll().map((e) => e.clientEventId), ['a', 'b']);
  } finally {
    cleanup();
  }
});

test('remove: 완료 id 파일 삭제, rejected 는 dead-letter 로 이동', () => {
  const { q, cleanup } = tempQueue();
  try {
    q.enqueue(ev('a'));
    q.enqueue(ev('b'));
    q.enqueue(ev('c'));
    q.remove(new Set(['a']), [ev('c')]); // a 삭제, c 는 dead-letter(이동)
    assert.deepEqual(q.readAll().map((e) => e.clientEventId), ['b']); // a·c 는 pending 에서 빠지고 b 유지
  } finally {
    cleanup();
  }
});

test('accountNamespace: 토큰/서버 다르면 다른 네임스페이스(계정 격리)', () => {
  assert.equal(accountNamespace('https://a', 'eaa_1'), accountNamespace('https://a', 'eaa_1'));
  assert.notEqual(accountNamespace('https://a', 'eaa_1'), accountNamespace('https://a', 'eaa_2'));
  assert.notEqual(accountNamespace('https://a', 'eaa_1'), accountNamespace('https://b', 'eaa_1'));
});
