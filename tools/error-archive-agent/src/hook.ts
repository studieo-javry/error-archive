// Claude Code Hook 어댑터(P1-4) — Hook 이 stdin 으로 넘기는 JSON 을 정규화 이벤트로 변환.
//
// Claude Code 는 Hook 커맨드에 JSON(stdin)을 준다: { session_id, cwd, hook_event_name, tool_name?,
// tool_input?, tool_response?, error?, prompt? }. 여기서 수집 대상만 골라 마스킹·절단해 EventItemRequest 로.
// 수집 대상(ARC): PostToolUse(도구 성공 결과) + PostToolUseFailure(도구 실패 — 에러 신호) + Stop(세션 종료).
//
// 중요: 현재 Claude Code 에서 도구 **실패**는 PostToolUse 가 아니라 PostToolUseFailure 로 발화하며,
// 이 이벤트엔 tool_response 가 없고 error(문자열, Bash 는 "Exit code N" + 합본 출력)만 온다.
// Error Archive 가 가장 원하는 신호(실패)를 놓치지 않으려면 둘 다 수집해야 한다. 다만 모든 "테스트 실패"가
// PostToolUseFailure 로 오는 건 아니므로(종료코드 0 인데 결과에 실패가 담길 수 있음) PostToolUse 결과도 함께 수집한다.

import { newUuid } from './idempotency.js';
import { maskAndTruncate } from './mask.js';
import type { EventItemRequest, JsonValue } from './types.js';

export interface HookInput {
  session_id?: string;
  cwd?: string;
  hook_event_name?: string;
  tool_name?: string;
  tool_input?: unknown;
  tool_response?: unknown;
  /** PostToolUseFailure 전용 — 실패 메시지(문자열). tool_response 는 없다. */
  error?: unknown;
  prompt?: string;
}

const COLLECTED_EVENTS = new Set(['PostToolUse', 'PostToolUseFailure', 'Stop']);

/** 이 Hook 이벤트를 수집할지(수집 대상 아니면 무시). */
export function shouldCollect(input: HookInput): boolean {
  return typeof input.hook_event_name === 'string' && COLLECTED_EVENTS.has(input.hook_event_name);
}

/** Hook JSON → 정규화 이벤트. redactedPayload 는 마스킹·절단된다. */
export function normalizeHookEvent(input: HookInput, localSequence: number): EventItemRequest {
  const base = input.hook_event_name ?? 'Unknown';
  const eventType = input.tool_name ? `${base}:${input.tool_name}` : base;
  const rawPayload: JsonValue = {
    hook: base,
    tool: input.tool_name ?? null,
    input: toJsonValue(input.tool_input),
    response: toJsonValue(input.tool_response),
    // PostToolUseFailure 는 tool_response 대신 error 로 실패를 싣는다 — 실패 신호를 보존한다.
    error: input.error == null ? null : toJsonValue(input.error),
    prompt: input.prompt ?? null,
  };
  const { payload, truncated } = maskAndTruncate(rawPayload);
  return {
    clientEventId: newUuid(),
    eventType,
    localSequence,
    observedAt: new Date().toISOString(),
    redactedPayload: payload,
    truncated,
    evidenceRefs: [],
    clientKind: 'claude-code',
    sessionId: input.session_id ?? null,
    toolCallId: readToolCallId(input.tool_input),
  };
}

function readToolCallId(toolInput: unknown): string | null {
  if (toolInput && typeof toolInput === 'object' && 'id' in toolInput) {
    const id = (toolInput as { id?: unknown }).id;
    return typeof id === 'string' ? id : null;
  }
  return null;
}

/** 임의 unknown 을 안전한 JsonValue 로(순환/함수 등은 null). */
function toJsonValue(v: unknown): JsonValue {
  try {
    return JSON.parse(JSON.stringify(v ?? null)) as JsonValue;
  } catch {
    return null;
  }
}
