import assert from 'node:assert/strict';
import { test } from 'node:test';
import { enqueueHookInput } from '../src/hooks/run.js';
import { QueueStore } from '../src/queue/store.js';
import type { EventItemRequest } from '../src/types.js';

function store() {
  return new QueueStore(':memory:');
}

test('enqueueHookInput: 지원 이벤트는 INGEST_EVENT 로 큐에 적재', () => {
  const q = store();
  const input = JSON.stringify({
    session_id: 's', hook_event_name: 'PostToolUse', tool_name: 'Edit',
    tool_input: { file_path: 'a.ts' }, tool_response: { ok: true }, tool_use_id: 'toolu_1',
  });
  const r = enqueueHookInput(input, q, {}, 1000);
  assert.equal(r, 'enqueued');
  const item = q.claim(1000, 1000)!;
  assert.equal(item.op, 'INGEST_EVENT');
  const ev = item.payload as EventItemRequest;
  assert.equal(ev.clientEventId, 'toolu_1');
  assert.equal(ev.eventType, 'PostToolUse');
  q.close();
});

test('enqueueHookInput: 제외/미지원/비JSON 은 skipped(큐 변화 없음)', () => {
  const q = store();
  assert.equal(enqueueHookInput('not json', q, {}, 1000), 'skipped');
  assert.equal(enqueueHookInput(JSON.stringify({ hook_event_name: 'PreToolUse' }), q, {}, 1000), 'skipped');
  assert.equal(enqueueHookInput('', q, {}, 1000), 'skipped');
  assert.equal(q.counts().PENDING, 0);
  q.close();
});

test('enqueueHookInput: ERROR_ARCHIVE_PROJECT_BINDING 을 이벤트에 주입', () => {
  const q = store();
  const input = JSON.stringify({ hook_event_name: 'Stop', stop_hook_active: false });
  enqueueHookInput(input, q, { ERROR_ARCHIVE_PROJECT_BINDING: '42' }, 2000);
  const item = q.claim(1000, 2000)!;
  assert.equal(item.projectBindingId, 42);
  assert.equal((item.payload as EventItemRequest).projectBindingId, 42);
  q.close();
});

test('enqueueHookInput: 토큰이 있으면 connectionId 는 안정 해시(원문 비노출)', () => {
  const q = store();
  const env = { ERROR_ARCHIVE_AGENT_TOKEN: 'eaa_supersecrettoken' };
  const input = JSON.stringify({ hook_event_name: 'Stop', stop_hook_active: false });
  enqueueHookInput(input, q, env, 3000);
  const item = q.claim(1000, 3000)!;
  assert.ok(item.connectionId.length > 0);
  assert.ok(!item.connectionId.includes('supersecret')); // 토큰 원문이 아님
  q.close();
});
