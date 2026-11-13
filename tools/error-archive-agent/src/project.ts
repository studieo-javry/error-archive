// 로컬 프로젝트 식별 — 작업 폴더 → git root/remote → 안정적인 projectKey.
//
// 설계(피드백 반영):
//  - 경로 신뢰 순위: 명시 경로 > config.projectRoot > MCP roots(정확히 1개일 때만) > process.cwd()(저신뢰).
//    roots 는 2026-07 명세에서 deprecated + 다중 워크스페이스 가능성 → 주 경로로 삼지 않는다.
//  - projectKey: git remote 정규화(git@ ↔ https 동일) 우선 → 없으면 경로 해시 기반 local-only 키(이름 충돌 방지).
//  - process.cwd() 가 git 저장소라는 이유만으로 "그 프로젝트"로 확정하지 않는다(승인 단계가 가린다).

import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { basename } from 'node:path';

export type RootTrust = 'explicit' | 'config' | 'hook' | 'roots' | 'cwd';

export interface ResolvedProject {
  /** git 최상위 경로(승인 상태의 키). */
  gitRoot: string;
  /** 정규화된 remote(github.com/org/repo). 없으면 null(local-only). */
  remote: string | null;
  /** 안정적 프로젝트 식별자 — remote 정규화 또는 local:<hash>:<name>. */
  projectKey: string;
  /** 경로 출처 신뢰도. cwd 는 저신뢰(잘못된 프로젝트일 수 있음). */
  trust: RootTrust;
}

/**
 * git remote URL 을 `host/path`(소문자 host, `.git`·선행슬래시 제거)로 정규화.
 * scp 형식(git@github.com:org/repo.git)과 https/ssh URL 을 **같은 키**로 만든다.
 */
export function normalizeRemote(url: string | null | undefined): string | null {
  const s = url?.trim();
  if (!s) return null;
  let host: string;
  let path: string;
  const scp = s.match(/^[A-Za-z0-9._-]+@([A-Za-z0-9._-]+):(.+)$/); // git@host:org/repo(.git)
  if (scp) {
    host = scp[1]!;
    path = scp[2]!;
  } else {
    let u: URL;
    try {
      u = new URL(s);
    } catch {
      return null;
    }
    host = u.host; // host[:port]
    path = u.pathname;
  }
  path = path.replace(/^\/+/, '').replace(/\/+$/, '').replace(/\.git$/i, '');
  host = host.toLowerCase();
  if (!path) return null;
  return `${host}/${path}`;
}

/** local-only(원격 없음) 프로젝트 키 — 경로 해시로 다른 폴더의 동명 충돌 방지. */
export function localProjectKey(gitRoot: string): string {
  const hash = createHash('sha256').update(gitRoot).digest('hex').slice(0, 12);
  return `local:${hash}:${basename(gitRoot)}`;
}

function runGit(cwd: string, args: string[]): string | null {
  try {
    return execFileSync('git', args, {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim();
  } catch {
    return null;
  }
}

export interface ResolveOptions {
  /** 도구 인자·Hook 등으로 명시 전달된 현재 작업 경로(최우선). */
  explicitPath?: string;
  /** 사용자가 설정한 projectRoot. */
  configRoot?: string;
  /** Claude Code Hook 이 보고한 실제 작업 경로(세션 문맥 핸드오프, G). config 다음 우선. */
  hookPath?: string;
  /** MCP roots 로 받은 경로들(파일 경로로 변환된 상태). 정확히 1개일 때만 사용. */
  roots?: string[];
  /** 저신뢰 폴백. */
  cwd?: string;
}

/** 우선순위대로 git 저장소를 찾아 ResolvedProject 반환. 어느 후보도 git 저장소가 아니면 null. */
export function resolveProject(opts: ResolveOptions): ResolvedProject | null {
  const candidates: Array<[string | undefined, RootTrust]> = [
    [opts.explicitPath, 'explicit'],
    [opts.configRoot, 'config'],
    [opts.hookPath, 'hook'],
    [opts.roots && opts.roots.length === 1 ? opts.roots[0] : undefined, 'roots'],
    [opts.cwd, 'cwd'],
  ];
  for (const [path, trust] of candidates) {
    if (!path) continue;
    const gitRoot = runGit(path, ['rev-parse', '--show-toplevel']);
    if (!gitRoot) continue;
    const remote = normalizeRemote(runGit(gitRoot, ['remote', 'get-url', 'origin']));
    return { gitRoot, remote, projectKey: remote ?? localProjectKey(gitRoot), trust };
  }
  return null;
}
