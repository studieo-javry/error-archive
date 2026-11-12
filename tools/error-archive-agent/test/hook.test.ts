import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeHookEvent, shouldCollect } from '../src/hook.js';

test('shouldCollect: PostToolUse·Stop 만 수집', () => {
  assert.equal(shouldCollect({ hook_event_name: 'PostToolUse' }), true);
  assert.equal(shouldCollect({ hook_event_name: 'Stop' }), true);
  assert.equal(shouldCollect({ hook_event_name: 'PreToolUse' }), false);
  assert.equal(shouldCollect({}), false);
});

test('normalizeHookEvent: eventType·sessionId·마스킹 payload', () => {
  const e = normalizeHookEvent(
    { hook_event_name: 'PostToolUse', tool_name: 'Bash', session_id: 'sess-1', tool_input: { command: 'echo eaa_deadbeef12345678' } },
    5,
  );
  assert.equal(e.eventType, 'PostToolUse:Bash');
  assert.equal(e.sessionId, 'sess-1');
  assert.equal(e.localSequence, 5);
  assert.equal(e.clientKind, 'claude-code');
  assert.match(JSON.stringify(e.redactedPayload), /\[REDACTED\]/); // 시크릿 마스킹됨
  assert.ok(e.clientEventId.length > 0);
});

test('normalizeHookEvent: tool_name 없으면 hook 이름만', () => {
  const e = normalizeHookEvent({ hook_event_name: 'Stop', session_id: 's' }, 1);
  assert.equal(e.eventType, 'Stop');
});
