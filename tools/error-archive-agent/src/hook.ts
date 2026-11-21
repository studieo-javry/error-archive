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

/**
 * 데이터 수집 활성 여부(Q4) — 프로젝트 **귀속 승인**과 분리된 **수집 토글**. 끄면 이벤트 적재를 멈춘다
 * (문맥 기록은 유지). 기본 on(hook 설치 자체가 명시적 opt-in). `ERROR_ARCHIVE_COLLECT=off|0|false|no` 로 중지.
 */
export function collectionEnabled(env: NodeJS.ProcessEnv = process.env): boolean {
  const v = (env.ERROR_ARCHIVE_COLLECT ?? 'on').trim().toLowerCase();
  return !(v === 'off' || v === '0' || v === 'false' || v === 'no');
}

/** 기본 민감 패턴 — 이 경로/명령을 건드리는 도구 이벤트는 수집에서 제외(마스킹과 별개의 2차 방어). */
const DEFAULT_SENSITIVE = [
  '.env', 'id_rsa', 'id_ed25519', '.pem', '.p12', '.pfx', 'credentials',
  '.npmrc', '.pypirc', '.aws/', '.ssh/', '.kube/config', '.git-credentials',
];

/** 사용자 추가 제외 패턴(ERROR_ARCHIVE_COLLECT_EXCLUDE, 콤마 구분, 소문자 부분일치). */
export function sensitivePatterns(env: NodeJS.ProcessEnv = process.env): string[] {
  const extra = (env.ERROR_ARCHIVE_COLLECT_EXCLUDE ?? '')
    .split(',')
    .map((s) => s.trim().toLowerCase())
    .filter((s) => s.length > 0);
  return [...DEFAULT_SENSITIVE, ...extra];
}

/** 이 이벤트의 tool_input 이 민감 경로/명령을 건드려 수집 제외해야 하는지. */
export function isSensitive(input: HookInput, patterns: string[] = sensitivePatterns()): boolean {
  if (input.tool_input == null) return false;
  let hay: string;
  try {
    hay = JSON.stringify(input.tool_input).toLowerCase();
  } catch {
    return false;
  }
  return patterns.some((p) => hay.includes(p));
}

/** 이 커넥터 자신의 MCP 도구 이름 접두사 — capture_*, diagnose, connect_project, *_events 등. */
const SELF_TOOL_PREFIX = 'mcp__error-archive__';

/**
 * 커넥터 **자기 도구 호출**(capture 작성·조회·연결·진단)은 '기록 행위'일 뿐 해결 과정의 '증거'가 아니다 → 수집 제외.
 *
 * 수집하면 (1) 순수 노이즈이고, (2) capture_complete 의 `unseenOnServer` 를 매 호출마다 키워 — 캡처를
 * 쓰는 행위 자체가 또 미확인 이벤트를 만들어 — 봉인을 영구히 막는다(force 강행을 강요). 둘 다 여기서 차단한다.
 * error-archive 도구를 로드하는 ToolSearch(select/키워드) 호출도 같은 메타라 함께 제외한다.
 */
export function isSelfReferential(input: HookInput): boolean {
  const tool = input.tool_name ?? '';
  if (tool.startsWith(SELF_TOOL_PREFIX)) return true;
  if (tool === 'ToolSearch') {
    try {
      const q = JSON.stringify(input.tool_input ?? '').toLowerCase();
      if (q.includes('error-archive') || q.includes('capture_')) return true;
    } catch {
      /* ignore */
    }
  }
  return false;
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
