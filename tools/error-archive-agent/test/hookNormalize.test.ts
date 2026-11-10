import assert from 'node:assert/strict';
import { test } from 'node:test';
import { normalizeHookEvent, OWN_MCP_PREFIX } from '../src/hooks/normalize.js';

const NOW = 1_762_000_000_000; // 고정 epoch ms
const OPTS = { now: NOW, fallbackEventId: 'fb-uuid' };

// 공식 hooks 레퍼런스(code.claude.com/docs/en/hooks)의 stdin 입력 스키마로 만든 계약 픽스처.

test('PostToolUse: tool_use_id 를 clientEventId 로, 입력/응답을 payload 로', () => {
  const ev = normalizeHookEvent(
    {
      session_id: 'abc123',
      cwd: '/repo',
      permission_mode: 'default',
      hook_event_name: 'PostToolUse',
      tool_name: 'Write',
      tool_input: { file_path: '/path/file.txt', content: 'x' },
      tool_response: { filePath: '/path/file.txt', type: 'create' },
      tool_use_id: 'toolu_01ABC',
      duration_ms: 12,
    },
    OPTS,
  )!;
  assert.ok(ev);
  assert.equal(ev.eventType, 'PostToolUse');
  assert.equal(ev.clientEventId, 'toolu_01ABC'); // tool_use_id 우선
  assert.equal(ev.toolCallId, 'toolu_01ABC');
  assert.equal(ev.sessionId, 'abc123');
  assert.equal(ev.clientKind, 'claude-code');
  assert.equal(ev.observedAt, new Date(NOW).toISOString());
  assert.equal(ev.localSequence, NOW);
  const p = ev.redactedPayload as Record<string, unknown>;
  assert.equal(p.toolName, 'Write');
  assert.deepEqual(p.toolInput, { file_path: '/path/file.txt', content: 'x' });
  assert.deepEqual(p.toolResponse, { filePath: '/path/file.txt', type: 'create' });
});

test('PostToolUseFailure: error 문자열과 is_interrupt 를 담는다', () => {
  const ev = normalizeHookEvent(
    {
      session_id: 'abc123',
      hook_event_name: 'PostToolUseFailure',
      tool_name: 'Bash',
      tool_input: { command: 'npm test', description: 'Run tests' },
      tool_use_id: 'toolu_02',
      error: 'Exit code 1\nError: Cannot find module',
      is_interrupt: false,
      duration_ms: 4187,
    },
    OPTS,
  )!;
  assert.equal(ev.eventType, 'PostToolUseFailure');
  const p = ev.redactedPayload as Record<string, unknown>;
  assert.match(p.error as string, /Exit code 1/);
  assert.equal(p.isInterrupt, false);
});

test('PreCompact: trigger/custom_instructions', () => {
  const ev = normalizeHookEvent(
    { session_id: 's', hook_event_name: 'PreCompact', trigger: 'manual', custom_instructions: 'keep API notes' },
    OPTS,
  )!;
  assert.equal(ev.eventType, 'PreCompact');
  assert.equal(ev.clientEventId, 'fb-uuid'); // tool_use_id 없음 → fallback
  const p = ev.redactedPayload as Record<string, unknown>;
  assert.equal(p.trigger, 'manual');
  assert.equal(p.customInstructions, 'keep API notes');
});

test('Stop: stop_hook_active/last_assistant_message, checkpoint 성격', () => {
  const ev = normalizeHookEvent(
    { session_id: 's', hook_event_name: 'Stop', stop_hook_active: true, last_assistant_message: '완료했습니다' },
    OPTS,
  )!;
  assert.equal(ev.eventType, 'Stop');
  const p = ev.redactedPayload as Record<string, unknown>;
  assert.equal(p.stopHookActive, true);
  assert.equal(p.lastAssistantMessage, '완료했습니다');
});

test('제외: 우리 MCP 서버 도구 호출은 수집하지 않는다(null)', () => {
  const ev = normalizeHookEvent(
    { hook_event_name: 'PostToolUse', tool_name: `${OWN_MCP_PREFIX}capture_start`, tool_input: {}, tool_use_id: 't' },
    OPTS,
  );
  assert.equal(ev, null);
});

test('제외: 커넥터 CLI 를 부르는 Bash 명령은 수집하지 않는다', () => {
  const ev = normalizeHookEvent(
    { hook_event_name: 'PostToolUse', tool_name: 'Bash', tool_input: { command: 'error-archive-agent status' }, tool_use_id: 't' },
    OPTS,
  );
  assert.equal(ev, null);
});

test('제외: 미지원 hook_event_name 은 null', () => {
  assert.equal(normalizeHookEvent({ hook_event_name: 'UserPromptSubmit' }, OPTS), null);
  assert.equal(normalizeHookEvent({ hook_event_name: 'PreToolUse', tool_name: 'Bash' }, OPTS), null);
  assert.equal(normalizeHookEvent({}, OPTS), null);
  assert.equal(normalizeHookEvent(null, OPTS), null);
});

test('마스킹: tool_input 안의 비밀은 가려진다', () => {
  const ev = normalizeHookEvent(
    {
      hook_event_name: 'PostToolUse',
      tool_name: 'Bash',
      tool_input: { command: 'curl -H "Authorization: Bearer secret123tok" https://x' },
      tool_use_id: 't',
    },
    OPTS,
  )!;
  const p = ev.redactedPayload as Record<string, unknown>;
  const input = p.toolInput as Record<string, unknown>;
  assert.ok(!(input.command as string).includes('secret123tok'));
});
