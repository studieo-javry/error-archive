// 프로젝트 문맥 + 세션 수명 관리(G3). 승인된 프로젝트를 세션에 실어 Capture 가 상속하게 한다.
//
// 설계(피드백 반영):
//  - sessionUuid: 가능하면 **클라이언트 세션 id**(config.sessionId) 사용, 없으면 프로세스 단위 UUID.
//    (MCP 프로세스 수명 ≠ Claude Code 세션 수명 — 동일 가정 금지.)
//  - 세션은 **지연 등록**: 첫 사용 시 register. 승인 프로젝트가 **바뀌면 재등록**(새 Idempotency-Key).
//    이미 생성된 Capture 는 소급 변경하지 않음(서버 정책).
//  - 등록 실패는 삼키고 sessionUuid 를 반환한다(서버는 미등록 세션을 무시 → Capture 는 미귀속으로 안전).
//  - 로컬 식별 경로는 승인 전까지 자동 귀속하지 않는다(approvedKey 가 null 이면 projectKey 미전송).

import { createHash } from 'node:crypto';
import type { ApiClient } from './apiClient.js';
import { newIdempotencyKey, newUuid } from './idempotency.js';
import { resolveProject, type ResolvedProject } from './project.js';
import { approve as persistApprove, getApproved, namespaceOf, revoke as persistRevoke } from './projectState.js';

export interface ProjectContextConfig {
  baseUrl: string;
  /** 사용자가 설정한 프로젝트 루트(선택). */
  projectRoot?: string;
  /** 클라이언트 세션 id(선택) — 있으면 sessionUuid 로 사용. */
  sessionId?: string;
  /** 승인 상태 파일 경로 override(테스트용). */
  statePath?: string;
  /** cwd override(테스트용). */
  cwd?: string;
  /** 프로젝트 해석 주입(테스트용 — 기본은 git 기반 resolveProject). */
  resolve?: (explicitPath?: string) => ResolvedProject | null;
}

export class ProjectContext {
  private userId?: number;
  private cachedResolve?: ResolvedProject | null;
  /** 세션 식별자. revoke/철회 시 로테이션되므로 가변. */
  private sessionUuid: string;
  /** 마지막으로 세션에 등록한 projectKey. undefined=미등록, null=프로젝트 없이 등록됨. */
  private lastRegisteredKey: string | null | undefined = undefined;

  constructor(
    private readonly client: ApiClient,
    private readonly cfg: ProjectContextConfig,
  ) {
    const sid = cfg.sessionId?.trim();
    this.sessionUuid = sid && sid.length > 0 ? deriveUuid(sid) : newUuid();
  }

  private resolveAt(explicitPath?: string): ResolvedProject | null {
    if (this.cfg.resolve) return this.cfg.resolve(explicitPath);
    if (explicitPath) return resolveProject({ explicitPath });
    return resolveProject({ configRoot: this.cfg.projectRoot, cwd: this.cfg.cwd ?? process.cwd() });
  }

  /** git 식별. 무인자는 프로세스 1회 캐시(gitRoot 안정적). 명시 경로는 즉시 해석(캐시 안 함). */
  detect(explicitPath?: string): ResolvedProject | null {
    if (explicitPath) return this.resolveAt(explicitPath);
    if (this.cachedResolve === undefined) {
      this.cachedResolve = this.resolveAt();
    }
    return this.cachedResolve;
  }

  private async namespace(): Promise<string | null> {
    if (this.userId == null) {
      try {
        this.userId = (await this.client.whoami()).userId;
      } catch {
        return null;
      }
    }
    return namespaceOf(this.cfg.baseUrl, this.userId);
  }

  /** 승인된 프로젝트의 projectKey(없거나 미승인/ remote 변경이면 null). 승인 상태는 매번 재확인. */
  async approvedProjectKey(): Promise<string | null> {
    const p = this.detect();
    if (!p) return null;
    const ns = await this.namespace();
    if (!ns) return null;
    return getApproved(ns, p.gitRoot, p.remote, { path: this.cfg.statePath })?.projectKey ?? null;
  }

  async isApproved(p: ResolvedProject): Promise<boolean> {
    const ns = await this.namespace();
    if (!ns) return false;
    return getApproved(ns, p.gitRoot, p.remote, { path: this.cfg.statePath }) != null;
  }

  /** 실제 사용자 승인 후 호출 — 로컬 저장 + 승인한 프로젝트를 현재 문맥으로 고정 + 재등록 유도. */
  async approve(p: ResolvedProject): Promise<void> {
    const ns = await this.namespace();
    if (!ns) throw new Error('계정(네임스페이스) 식별 실패 — 토큰/서버 설정을 확인하세요.');
    persistApprove(ns, p.gitRoot, { projectKey: p.projectKey, remote: p.remote }, { path: this.cfg.statePath });
    // 명시 경로로 승인했어도 이후 ensureSession(무인자 detect)이 같은 프로젝트를 쓰도록 문맥 고정(②).
    this.cachedResolve = p;
    this.lastRegisteredKey = undefined; // 재등록 유도
  }

  /**
   * 연결 해제 — 로컬 승인 삭제 + **새 sessionUuid 로 로테이션**(①). 서버의 세션 바인딩은 null 로 비울 수
   * 없으므로(계약상 no-op), 기존 바운드 세션을 버리고 새 미귀속 세션을 쓴다. 기존 Capture 는 소급 변경 안 함.
   */
  async revoke(p: ResolvedProject): Promise<void> {
    const ns = await this.namespace();
    if (!ns) return;
    persistRevoke(ns, p.gitRoot, { path: this.cfg.statePath });
    this.sessionUuid = newUuid();
    this.lastRegisteredKey = undefined;
  }

  /**
   * Capture 에 사용할 sessionUuid 를 돌려준다. 승인 프로젝트가 바뀌었으면 세션을 (재)등록한다.
   * 등록 실패는 삼킨다(서버가 미등록 세션 무시 → Capture 미귀속).
   */
  async ensureSession(): Promise<string> {
    const desired = await this.approvedProjectKey();
    if (this.lastRegisteredKey !== desired) {
      // 바운드→미귀속 전이(승인 철회·remote 변경 등, revoke 미경유 포함): 기존 세션 바인딩을 비울 수 없으므로
      // 새 sessionUuid 로 로테이션해 미귀속 세션을 새로 쓴다(①).
      if (desired == null && typeof this.lastRegisteredKey === 'string') {
        this.sessionUuid = newUuid();
      }
      try {
        await this.client.registerSession(
          {
            sessionUuid: this.sessionUuid,
            clientKind: 'claude-code',
            clientSessionId: this.cfg.sessionId ?? this.sessionUuid,
            projectKey: desired ?? undefined,
          },
          newIdempotencyKey(),
        );
        this.lastRegisteredKey = desired;
      } catch (err) {
        process.stderr.write(`[project] 세션 등록 실패(무시, Capture 는 미귀속): ${err instanceof Error ? err.message : String(err)}\n`);
      }
    }
    return this.sessionUuid;
  }
}

/** 클라이언트 세션 id 를 안정적 UUID 로(서버 sessionUuid 는 UUID 형식). 이미 UUID 면 그대로. */
function deriveUuid(sessionId: string): string {
  const uuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (uuidRe.test(sessionId)) return sessionId;
  // 비-UUID 세션 id 는 결정적으로 UUID 형식으로 매핑(같은 세션 id → 같은 uuid). 버전/variant 비트만 고정.
  const h = createHash('sha256').update(sessionId).digest('hex'); // 64 hex
  const variant = ((parseInt(h.slice(16, 17), 16) & 0x3) | 0x8).toString(16);
  return [h.slice(0, 8), h.slice(8, 12), `4${h.slice(13, 16)}`, `${variant}${h.slice(17, 20)}`, h.slice(20, 32)].join('-');
}
