/**
 * Claude Code Hook stdin payload → 정규화된 CaptureEvent(EventItemRequest). 순수 함수(부수효과 없음).
 * 공식 hooks 레퍼런스(https://code.claude.com/docs/en/hooks)의 stdin 입력 스키마(snake_case)를 따른다.
 *
 * 지원 이벤트: PostToolUse / PostToolUseFailure / PreCompact / Stop.
 *  - PostToolUse 는 성공한 도구 호출 뒤에만, PostToolUseFailure 는 실패 뒤에만 발생(실패는 PostToolUse 에 안 섞임).
 *  - PreCompact·Stop 은 **checkpoint·flush 계기**일 뿐 complete(봉인) 근거가 아니다.
 *
 * 제외(수집 루프 방지, #145 §1):
 *  - 우리 MCP 서버 도구(`mcp__error-archive-agent__*`) 호출 — 기록 행위 자체를 기록하지 않는다.
 *  - 커넥터 CLI 를 부르는 Bash 명령(`error-archive-agent …`).
 *  - 미지원 hook_event_name — 범위 밖이므로 수집하지 않는다(null).
 */
import type { EventItemRequest } from '../types.js';
import { maskAndCap } from './mask.js';

/** 우리 MCP 서버 이름(server.ts 의 McpServer name 과 일치). 이 접두사의 도구 호출은 수집 제외. */
export const OWN_MCP_PREFIX = 'mcp__error-archive-agent__';
/** 커넥터 CLI 바이너리 이름(자기 호출 Bash 제외용). */
export const OWN_CLI_NAME = 'error-archive-agent';

const SUPPORTED = new Set(['PostToolUse', 'PostToolUseFailure', 'PreCompact', 'Stop']);

export interface NormalizeOptions {
  /** 이벤트 관측 시각(epoch ms). observedAt(ISO) + localSequence 로 쓴다. */
  now: number;
  /** tool_use_id 가 없는 이벤트(PreCompact/Stop)의 clientEventId 대체값. 호출 측이 UUID 를 준다. */
  fallbackEventId: string;
  /** 설정에서 주입하는 프로젝트 바인딩(없으면 null). */
  projectBindingId?: number | null;
}

type Json = Record<string, unknown>;

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

/** 수집에서 제외할 도구 호출인가(우리 자신). */
function isOwnActivity(toolName: string | undefined, toolInput: unknown): boolean {
  if (toolName && toolName.startsWith(OWN_MCP_PREFIX)) return true;
  if (toolName === 'Bash' && toolInput && typeof toolInput === 'object') {
    const cmd = (toolInput as Json).command;
    if (typeof cmd === 'string' && cmd.includes(OWN_CLI_NAME)) return true;
  }
  return false;
}

/**
 * Hook payload 를 CaptureEvent 로 정규화한다. 수집 제외·미지원이면 null.
 * payload 는 마스킹 후 64 KiB 로 상한(초과 시 truncated=true).
 */
export function normalizeHookEvent(raw: unknown, opts: NormalizeOptions): EventItemRequest | null {
  if (!raw || typeof raw !== 'object') return null;
  const hook = raw as Json;
  const eventType = str(hook.hook_event_name);
  if (!eventType || !SUPPORTED.has(eventType)) return null;

  const sessionId = str(hook.session_id);
  const toolName = str(hook.tool_name);
  const toolUseId = str(hook.tool_use_id);

  // 도구 이벤트면 우리 자신 활동 제외.
  if ((eventType === 'PostToolUse' || eventType === 'PostToolUseFailure') && isOwnActivity(toolName, hook.tool_input)) {
    return null;
  }

  const observedAt = new Date(opts.now).toISOString();
  // tool_use_id 가 있으면 그걸 clientEventId 로(도구 호출당 1개, 성공/실패 XOR → 멱등 안정).
  const clientEventId = toolUseId ?? opts.fallbackEventId;

  const rawPayload = buildPayload(eventType, hook);
  const { payload, truncated } = maskAndCap(rawPayload);

  return {
    clientEventId,
    eventType,
    localSequence: opts.now, // 클라이언트 관측 순서(동률은 서버 수신순서로). 세션 내 단조 증가.
    observedAt,
    redactedPayload: payload,
    projectBindingId: opts.projectBindingId ?? null,
    clientKind: 'claude-code',
    sessionId: sessionId ?? null,
    toolCallId: toolUseId ?? null,
    truncated,
    evidenceRefs: [],
  };
}

/** 이벤트 유형별로 의미 있는 필드만 추려 payload 로 만든다(마스킹 전 원본 선택). */
function buildPayload(eventType: string, hook: Json): unknown {
  switch (eventType) {
    case 'PostToolUse':
      return {
        toolName: hook.tool_name,
        toolInput: hook.tool_input,
        toolResponse: hook.tool_response,
        durationMs: hook.duration_ms,
      };
    case 'PostToolUseFailure':
      return {
        toolName: hook.tool_name,
        toolInput: hook.tool_input,
        error: hook.error,
        isInterrupt: hook.is_interrupt,
        durationMs: hook.duration_ms,
      };
    case 'PreCompact':
      return { trigger: hook.trigger, customInstructions: hook.custom_instructions };
    case 'Stop':
      return {
        stopHookActive: hook.stop_hook_active,
        lastAssistantMessage: hook.last_assistant_message,
      };
    default:
      return {};
  }
}
