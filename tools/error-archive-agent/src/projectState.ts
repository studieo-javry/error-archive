// 프로젝트 연결 "승인" 로컬 상태 — 최초 1회 사용자 승인 후 저장, 이후 자동 문맥 전달에 사용.
//
// 설계(피드백 반영):
//  - projectBindingId 가 아니라 **projectKey** 를 저장한다(SessionResponse 가 bindingId 를 반환하지 않음 →
//    서버가 projectKey 로 find-or-create. BE·계약 변경 없음).
//  - **계정별 격리**: 네임스페이스 = `${baseUrl}#${userId}`. 토큰(계정)을 바꾸면 이전 승인 재사용 금지.
//  - **remote 변경 무효화**: 같은 경로가 다른 저장소로 교체되면 저장된 remote 와 불일치 → 미승인으로 간주.
//  - 도구 호출만으로 저장하지 않는다(승인은 호출측에서 실제 사용자 확인 후 approve() 호출).

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';

export interface ApprovedProject {
  projectKey: string;
  remote: string | null;
  approvedAt: string;
}

/** namespace -> gitRoot -> 승인정보 */
type Store = Record<string, Record<string, ApprovedProject>>;

export function defaultStatePath(env: NodeJS.ProcessEnv = process.env): string {
  return env.ERROR_ARCHIVE_STATE ?? join(homedir(), '.config', 'error-archive-agent', 'projects.json');
}

/** 서버 주소 + 사용자 id 로 계정 네임스페이스. */
export function namespaceOf(baseUrl: string, userId: number | string): string {
  return `${baseUrl}#${userId}`;
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
  writeFileSync(path, `${JSON.stringify(store, null, 2)}\n`);
}

export interface StateOptions {
  path?: string;
}

/**
 * 승인된 프로젝트 조회. 저장된 remote 가 현재 remote 와 다르면(=저장소 교체) **미승인**(null)으로 본다.
 */
export function getApproved(
  namespace: string,
  gitRoot: string,
  currentRemote: string | null,
  opts: StateOptions = {},
): ApprovedProject | null {
  const store = load(opts.path ?? defaultStatePath());
  const entry = store[namespace]?.[gitRoot];
  if (!entry) return null;
  if ((entry.remote ?? null) !== (currentRemote ?? null)) return null; // remote 변경 → 재승인 필요
  return entry;
}

/** 실제 사용자 승인 후 저장(호출측이 승인 보장). */
export function approve(
  namespace: string,
  gitRoot: string,
  value: { projectKey: string; remote: string | null },
  opts: StateOptions = {},
): ApprovedProject {
  const path = opts.path ?? defaultStatePath();
  const store = load(path);
  const ns = (store[namespace] ??= {});
  const approved: ApprovedProject = { projectKey: value.projectKey, remote: value.remote, approvedAt: new Date().toISOString() };
  ns[gitRoot] = approved;
  persist(path, store);
  return approved;
}

/** 연결 해제(승인 취소). */
export function revoke(namespace: string, gitRoot: string, opts: StateOptions = {}): void {
  const path = opts.path ?? defaultStatePath();
  const store = load(path);
  if (store[namespace]) {
    delete store[namespace][gitRoot];
    persist(path, store);
  }
}
