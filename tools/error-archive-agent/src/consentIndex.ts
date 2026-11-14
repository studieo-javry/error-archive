// 토큰 네임스페이스 "동의 인덱스" (Phase 1) — 오프라인 Hook·Sender 가 승인 상태를 읽을 수 있게 하는 미러.
//
// 문제: 승인 원본(projectState)은 `baseUrl#userId` 네임스페이스라 userId 를 알아야(=whoami=온라인) 읽는다.
//       그런데 Hook·Sender 는 오프라인이라 userId 를 모른다(토큰만 있음). 그래서 승인 여부를 못 읽는다.
// 해법: approve()/revoke() 가 원본과 **함께** 이 인덱스를 `accountNamespace(baseUrl, token)`(토큰 해시,
//       오프라인 계산 가능) 네임스페이스로 미러한다. 수집 판정의 source of truth 는 이 인덱스다.
//
// 설계 규칙:
//  - **fail-closed**: 읽기 실패·레코드 없음·remote 불일치 → 미승인(수집 안 함).
//  - 원본 토큰은 저장하지 않는다(네임스페이스 해시만). projectState 와 동일하게 remote 변경 시 APPROVED 무효화.
//  - 상태: APPROVED(영속 승인) / SNOOZED(해당 세션만 안내 보류) / DECLINED(영속 거절). 레코드 없음 = UNAPPROVED.
//  - 원자적 temp+rename 쓰기. (동시 Hook/Sender 갱신의 완전 직렬화는 P0 범위 밖 — 읽기 최신본 + fail-closed 로 안전.)

import { mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

export type ConsentStatus = 'APPROVED' | 'SNOOZED' | 'DECLINED';

export interface ConsentRecord {
  projectKey: string;
  gitRoot: string;
  status: ConsentStatus;
  /** 승인(또는 기록) 시점의 git remote. remote 가 바뀌면 APPROVED 는 무효(재승인 필요). */
  remoteAtApproval: string | null;
  updatedAt: string;
  /** SNOOZED 일 때 보류 범위인 Claude Code 세션 id(세션 단위). 그 외 상태엔 없음/null. */
  snoozeSessionId?: string | null;
}

/** namespace -> gitRoot -> record */
type Store = Record<string, Record<string, ConsentRecord>>;

export function defaultConsentIndexPath(env: NodeJS.ProcessEnv = process.env): string {
  return env.ERROR_ARCHIVE_CONSENT_INDEX ?? join(homedir(), '.config', 'error-archive-agent', 'consent-index.json');
}

export interface ConsentOptions {
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
  renameSync(tmp, path); // 원자 교체 — 동시 쓰기 중 부분기록 방지
}

/** 원시 레코드 조회(상태 무관, remote 검사 없음). 없으면 null. */
export function readConsent(namespace: string, gitRoot: string, opts: ConsentOptions = {}): ConsentRecord | null {
  const store = load(opts.path ?? defaultConsentIndexPath());
  return store[namespace]?.[gitRoot] ?? null;
}

/**
 * 수집/귀속을 허용할지 — Hook·Sender·MCP 게이트의 단일 판정. **fail-closed**.
 * APPROVED 이고 저장된 remote 가 현재 remote 와 같을 때만 true. 그 외(없음·SNOOZED·DECLINED·remote 변경·오류)는 false.
 */
export function isCollectionApproved(
  namespace: string,
  gitRoot: string,
  currentRemote: string | null,
  opts: ConsentOptions = {},
): boolean {
  try {
    const rec = readConsent(namespace, gitRoot, opts);
    if (!rec || rec.status !== 'APPROVED') return false;
    return (rec.remoteAtApproval ?? null) === (currentRemote ?? null);
  } catch {
    return false; // 읽기/파싱 실패 → 미승인으로 간주(안전측)
  }
}

/** 이 세션에서 안내가 보류(SNOOZED)됐는지 — 최초 안내 반복 억제용(Phase 2). */
export function isSnoozedForSession(
  namespace: string,
  gitRoot: string,
  sessionId: string | null | undefined,
  opts: ConsentOptions = {},
): boolean {
  if (!sessionId) return false;
  const rec = readConsent(namespace, gitRoot, opts);
  return rec?.status === 'SNOOZED' && rec.snoozeSessionId === sessionId;
}

/** 상태 upsert(gitRoot 단위). updatedAt 은 호출 시각으로 갱신. */
export function setConsent(
  namespace: string,
  record: Omit<ConsentRecord, 'updatedAt'> & { updatedAt?: string },
  opts: ConsentOptions = {},
): ConsentRecord {
  const path = opts.path ?? defaultConsentIndexPath();
  const store = load(path);
  const ns = (store[namespace] ??= {});
  const saved: ConsentRecord = {
    projectKey: record.projectKey,
    gitRoot: record.gitRoot,
    status: record.status,
    remoteAtApproval: record.remoteAtApproval ?? null,
    snoozeSessionId: record.status === 'SNOOZED' ? (record.snoozeSessionId ?? null) : undefined,
    updatedAt: record.updatedAt ?? new Date().toISOString(),
  };
  ns[record.gitRoot] = saved;
  persist(path, store);
  return saved;
}

/** 레코드 삭제(→ UNAPPROVED). 연결 해제 시 수집 즉시 중단. */
export function clearConsent(namespace: string, gitRoot: string, opts: ConsentOptions = {}): void {
  const path = opts.path ?? defaultConsentIndexPath();
  const store = load(path);
  if (store[namespace] && gitRoot in store[namespace]) {
    delete store[namespace][gitRoot];
    persist(path, store);
  }
}
