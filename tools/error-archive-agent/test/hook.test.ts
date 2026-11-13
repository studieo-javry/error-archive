import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeHookEvent, shouldCollect } from '../src/hook.js';

test('shouldCollect: PostToolUse·PostToolUseFailure·Stop 만 수집', () => {
  assert.equal(shouldCollect({ hook_event_name: 'PostToolUse' }), true);
  assert.equal(shouldCollect({ hook_event_name: 'PostToolUseFailure' }), true);
  assert.equal(shouldCollect({ hook_event_name: 'Stop' }), true);
  assert.equal(shouldCollect({ hook_event_name: 'PreToolUse' }), false);
  // UserPromptSubmit 은 수집하지 않는다(원문 미저장 — index.runHook 에서 정책만 주입).
  assert.equal(shouldCollect({ hook_event_name: 'UserPromptSubmit' }), false);
  assert.equal(shouldCollect({}), false);
});

test('normalizeHookEvent: PostToolUseFailure 는 error 를 payload 에 보존(tool_response 없음)', () => {
  const e = normalizeHookEvent(
    {
      hook_event_name: 'PostToolUseFailure',
      tool_name: 'Bash',
      session_id: 'sess-9',
      tool_input: { command: './gradlew build' },
      error: 'Exit code 1\n> Task :compileKotlin FAILED\nunresolved reference: repositroy',
    },
    7,
  );
  assert.equal(e.eventType, 'PostToolUseFailure:Bash');
  const payload = e.redactedPayload as Record<string, unknown>;
  assert.equal(payload.response, null); // 실패 이벤트엔 tool_response 가 없다
  assert.match(String(payload.error), /unresolved reference/); // 실패 신호(에러)는 보존된다
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
