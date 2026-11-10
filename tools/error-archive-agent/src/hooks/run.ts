/**
 * `error-archive-agent hook` — Claude Code Hook 엔트리. stdin 으로 받은 hook payload 를 정규화해
 * **로컬 큐에만 적재**하고 즉시 끝난다. 네트워크·설정 없이 동작하며(전송은 sender/flush 담당),
 * 무슨 일이 있어도 **개발 명령 결과를 바꾸지 않는다** — 오류는 stderr 로 삼키고 항상 종료코드 0(#145 비차단).
 */
import { createHash, randomUUID } from 'node:crypto';
import { resolveQueuePath } from '../queue/path.js';
import { QueueStore } from '../queue/store.js';
import { QueueFullError } from '../queue/types.js';
import { normalizeHookEvent } from './normalize.js';

/** stdin 전체를 읽는다(상한·타임아웃으로 멈춤 방지). */
async function readStdin(limitBytes = 5 * 1024 * 1024, timeoutMs = 3000): Promise<string> {
  const chunks: Buffer[] = [];
  let size = 0;
  const stdin = process.stdin;
  const timer = setTimeout(() => stdin.destroy(), timeoutMs);
  try {
    for await (const chunk of stdin) {
      const buf = chunk as Buffer;
      size += buf.length;
      if (size > limitBytes) break; // 과대 입력은 잘라서라도 진행(정규화 단계에서 상한 재적용)
      chunks.push(buf);
    }
  } catch {
    /* stdin 파괴/오류 — 모은 만큼만 사용 */
  } finally {
    clearTimeout(timer);
  }
  return Buffer.concat(chunks).toString('utf8');
}

/** 설정이 있으면 토큰으로 안정적 로컬 연결 마커를 만든다(원문 비노출). 없으면 '' — 서버가 토큰으로 재귀속. */
function localConnMarker(env: NodeJS.ProcessEnv): string {
  const token = env.ERROR_ARCHIVE_AGENT_TOKEN;
  if (!token) return '';
  return createHash('sha256').update(token).digest('hex').slice(0, 16);
}

function projectBindingFromEnv(env: NodeJS.ProcessEnv): number | null {
  const raw = env.ERROR_ARCHIVE_PROJECT_BINDING;
  if (!raw) return null;
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : null;
}

/**
 * Hook 입력(JSON 문자열)을 정규화해 큐에 적재하는 순수 코어(테스트용). 비 JSON·제외·미지원이면 'skipped'.
 * stdin 읽기와 분리해 stdin 목킹 없이 검증한다.
 */
export function enqueueHookInput(
  input: string,
  store: QueueStore,
  env: NodeJS.ProcessEnv = process.env,
  now: number = Date.now(),
): 'enqueued' | 'skipped' {
  if (!input.trim()) return 'skipped';
  let parsed: unknown;
  try {
    parsed = JSON.parse(input);
  } catch {
    return 'skipped';
  }
  const event = normalizeHookEvent(parsed, {
    now,
    fallbackEventId: randomUUID(),
    projectBindingId: projectBindingFromEnv(env),
  });
  if (!event) return 'skipped';
  store.enqueue(
    {
      connectionId: localConnMarker(env),
      op: 'INGEST_EVENT',
      projectBindingId: event.projectBindingId ?? null,
      payload: event,
    },
    now,
  );
  return 'enqueued';
}

export async function runHook(env: NodeJS.ProcessEnv = process.env): Promise<number> {
  let store: QueueStore | undefined;
  try {
    const input = await readStdin();
    if (!input.trim()) return 0;
    store = new QueueStore(resolveQueuePath(env));
    enqueueHookInput(input, store, env); // 정규화·제외·멱등은 코어가 처리
    return 0;
  } catch (err) {
    if (err instanceof QueueFullError) {
      process.stderr.write(`[error-archive-agent] 로컬 큐가 가득 차 수집을 멈춥니다. 'error-archive-agent flush' 로 비우세요.\n`);
      return 0;
    }
    // 어떤 오류든 개발 흐름을 막지 않는다.
    process.stderr.write(`[error-archive-agent] hook 처리 중 오류(무시): ${err instanceof Error ? err.message : String(err)}\n`);
    return 0;
  } finally {
    store?.close();
  }
}
