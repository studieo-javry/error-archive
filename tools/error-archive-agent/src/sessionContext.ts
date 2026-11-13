// Hook ↔ MCP 서버 프로세스 간 "세션 문맥" 핸드오프(G) — Claude Code 의 실제 cwd·session_id 전달.
//
// 문제: Claude Code 의 진짜 작업 경로·session_id 를 아는 건 짧게 떴다 죽는 **Hook 프로세스**인데,
// Capture/세션 등록은 장수 **MCP 서버 프로세스**에서 일어난다. 둘은 메모리를 공유하지 않는다.
// → Hook 이 이 파일에 {sessionId, cwd} 를 기록하고, MCP 서버의 ProjectContext 가 읽어 문맥을 복원한다.
//
// 설계:
//  - 키: 계정 네임스페이스(eventQueue 와 동일 `accountNamespace` = baseUrl+token 해시). **네트워크 없음**
//    (Hook 은 whoami 를 호출하지 않는다) + 토큰 교체 시 섞이지 않음.
//  - session_id 별 엔트리 → 다중 동시 세션이 서로 덮어쓰지 않는다.
//  - 오래된/과다 엔트리 prune(24h·50개). atomic temp+rename(동시 Hook 쓰기 안전).

import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

/** session_id 없을 때의 예비 키(단일 세션 환경). */
const DEFAULT_SESSION_KEY = '(default)';
const MAX_AGE_MS = 24 * 60 * 60 * 1000; // 24h 넘은 문맥은 폐기
const MAX_ENTRIES = 50; // 네임스페이스당 최신 N개만 유지

export interface SessionContextEntry {
  /** Claude Code 세션 id(없으면 null — DEFAULT_SESSION_KEY 로 저장됨). */
  sessionId: string | null;
  /** Claude Code 가 보고한 실제 작업 경로. */
  cwd: string;
  /** ISO-8601 최종 갱신 시각. */
  updatedAt: string;
}

/** namespace -> sessionKey -> 문맥 */
type Store = Record<string, Record<string, SessionContextEntry>>;

export function defaultContextPath(env: NodeJS.ProcessEnv = process.env): string {
  return env.ERROR_ARCHIVE_SESSION_CONTEXT ?? join(homedir(), '.config', 'error-archive-agent', 'session-context.json');
}

export interface ContextOptions {
  path?: string;
}

function load(path: string): Store {
  try {
    const parsed = JSON.parse(readFileSync(path, 'utf8'));
    return parsed && typeof parsed === 'object' ? (parsed as Store) : {};
  } catch {
    return {};
  }
}

function persist(path: string, store: Store): void {
  mkdirSync(dirname(path), { recursive: true });
  const tmp = `${path}.tmp`;
  writeFileSync(tmp, `${JSON.stringify(store, null, 2)}\n`);
  renameSync(tmp, path); // 원자 교체 — 동시 Hook 쓰기 중 부분기록 방지
}

/** 네임스페이스 내 엔트리를 최신순 정렬하고 24h 초과·50개 초과를 잘라낸다. */
function prune(ns: Record<string, SessionContextEntry>): Record<string, SessionContextEntry> {
  const now = Date.now();
  const kept = Object.entries(ns)
    .filter(([, e]) => now - Date.parse(e.updatedAt) <= MAX_AGE_MS)
    .sort((a, b) => Date.parse(b[1].updatedAt) - Date.parse(a[1].updatedAt))
    .slice(0, MAX_ENTRIES);
  return Object.fromEntries(kept);
}

/**
 * Hook 프로세스가 호출 — 현재 {sessionId, cwd} 를 기록(upsert). cwd 가 없으면 무시(기록할 게 없음).
 * Hook 은 절대 실패로 종료하지 않으므로 호출측에서 try/catch 로 감싸는 것을 전제한다.
 */
export function recordContext(
  namespace: string,
  ctx: { sessionId?: string | null; cwd?: string | null },
  opts: ContextOptions = {},
): void {
  const cwd = ctx.cwd?.trim();
  if (!cwd) return;
  const path = opts.path ?? defaultContextPath();
  const store = load(path);
  const key = ctx.sessionId?.trim() || DEFAULT_SESSION_KEY;
  const ns = (store[namespace] ??= {});
  // updatedAt 은 "마지막으로 쓴 엔트리 = 최신" 을 보장하는 논리 시계로 쓴다. ms 해상도 벽시계는 빠른
  // 연속 쓰기에서 타이가 나 정렬이 삽입 순서로 깨지므로, 기존 최대보다 엄격히 크게 보정한다.
  const maxExisting = Object.values(ns).reduce((m, e) => Math.max(m, Date.parse(e.updatedAt) || 0), 0);
  const ts = Math.max(Date.now(), maxExisting + 1);
  ns[key] = { sessionId: ctx.sessionId?.trim() || null, cwd, updatedAt: new Date(ts).toISOString() };
  store[namespace] = prune(ns);
  persist(path, store);
}

export interface ResolvedSessionContext extends SessionContextEntry {
  /** preferSessionId 매칭 없이 복수 엔트리 중 최신을 고른 경우 true(다중 세션 모호). */
  ambiguous: boolean;
}

/**
 * MCP 서버가 호출 — 현재 세션 문맥을 복원한다.
 *  - preferSessionId(=config.sessionId 등 내 세션을 아는 경우)가 있으면 **그 엔트리만** 반환(없으면 null).
 *    내 세션 문맥이 없는데 다른(엉뚱한) 세션으로 폴백하면 다중 세션에서 잘못 바인딩되므로 폴백하지 않는다.
 *  - preferSessionId 가 없으면(내 세션 id 를 모르면) 가장 최근 갱신 엔트리. 복수 엔트리이면 ambiguous=true.
 */
export function readContext(
  namespace: string,
  opts: ContextOptions & { preferSessionId?: string } = {},
): ResolvedSessionContext | null {
  const store = load(opts.path ?? defaultContextPath());
  const ns = store[namespace];
  if (!ns) return null;
  const entries = Object.entries(ns);
  if (entries.length === 0) return null;

  const prefer = opts.preferSessionId?.trim();
  if (prefer) {
    const hit = ns[prefer];
    return hit ? { ...hit, ambiguous: false } : null; // 폴백 금지(엉뚱한 세션 방지)
  }
  // 내 세션 id 를 모름 → 최신 갱신 엔트리. 복수면 ambiguous.
  const newest = entries.sort((a, b) => Date.parse(b[1].updatedAt) - Date.parse(a[1].updatedAt))[0]![1];
  return { ...newest, ambiguous: entries.length > 1 };
}
