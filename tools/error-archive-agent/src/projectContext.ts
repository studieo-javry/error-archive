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
import { resolveProject, type ResolveOptions, type ResolvedProject } from './project.js';
import { clearConsent, setConsent } from './consentIndex.js';
import { approve as persistApprove, getApproved, namespaceOf, revoke as persistRevoke } from './projectState.js';
import { readContext, type ResolvedSessionContext } from './sessionContext.js';

export interface ProjectContextConfig {
  baseUrl: string;
  /** 사용자가 설정한 프로젝트 루트(선택). */
  projectRoot?: string;
  /** 클라이언트 세션 id(선택) — hook session_id 다음 순위로 sessionUuid 파생에 사용. */
  sessionId?: string;
  /** 승인 상태 파일 경로 override(테스트용). */
  statePath?: string;
  /**
   * 토큰 네임스페이스(accountNamespace(baseUrl, token)) — 오프라인 동의 인덱스 미러용. 설정되면 approve/revoke
   * 가 이 네임스페이스로 동의 인덱스를 함께 갱신해 Hook·Sender 가 네트워크 없이 수집 승인 여부를 읽는다.
   */
  consentNamespace?: string;
  /** 동의 인덱스 파일 경로 override(테스트용). */
  consentPath?: string;
  /** cwd override(테스트용). */
  cwd?: string;
  /** 프로젝트 해석 주입(테스트용 — 기본은 git 기반 resolveProject). */
  resolve?: (opts: ResolveOptions) => ResolvedProject | null;
  /** 세션 문맥 저장소 네임스페이스(accountNamespace). 설정되면 Hook cwd·session_id 를 읽어 문맥 복원(G). */
  hookNamespace?: string;
  /** 세션 문맥 저장소 경로 override(테스트용). */
  hookStatePath?: string;
  /** 세션 문맥 읽기 주입(테스트용 — 기본은 hookNamespace 로 readContext). */
  readHookContext?: () => ResolvedSessionContext | null;
}

export class ProjectContext {
  private userId?: number;
  private cachedResolve?: ResolvedProject | null;
  /** 세션 식별자. revoke/철회 시 로테이션되므로 가변. */
  private sessionUuid: string;
  /** 마지막으로 세션에 등록한 projectKey. undefined=미등록, null=프로젝트 없이 등록됨. */
  private lastRegisteredKey: string | null | undefined = undefined;
  /** 마지막으로 등록한 sessionUuid — session_id 채택으로 uuid 가 바뀌면 재등록 유도. */
  private lastRegisteredSessionUuid: string | undefined = undefined;
  /** detect() 캐시 무효화용 — 마지막으로 해석에 사용한 hook cwd(프로젝트 전환 감지). */
  private lastHookCwd: string | undefined = undefined;
  /** 해제/전이로 sessionUuid 를 로테이션한 상태. true 면 session_id 재채택을 막는다(미귀속 decouple 유지). */
  private rotated = false;

  constructor(
    private readonly client: ApiClient,
    private readonly cfg: ProjectContextConfig,
  ) {
    const sid = cfg.sessionId?.trim();
    this.sessionUuid = sid && sid.length > 0 ? deriveUuid(sid) : newUuid();
  }

  /** 세션 문맥(Hook 이 기록한 실제 cwd·session_id) 읽기. 없으면 null. */
  private readHook(): ResolvedSessionContext | null {
    if (this.cfg.readHookContext) return this.cfg.readHookContext();
    if (!this.cfg.hookNamespace) return null;
    return readContext(this.cfg.hookNamespace, { preferSessionId: this.cfg.sessionId, path: this.cfg.hookStatePath });
  }

  private resolveAt(explicitPath: string | undefined, hookCwd: string | undefined): ResolvedProject | null {
    // 우선순위: 명시 인자 > config.projectRoot > hook cwd > process.cwd(). (roots 는 미사용 — project.ts 참고)
    const opts: ResolveOptions = explicitPath
      ? { explicitPath }
      : { configRoot: this.cfg.projectRoot, hookPath: hookCwd, cwd: this.cfg.cwd ?? process.cwd() };
    return this.cfg.resolve ? this.cfg.resolve(opts) : resolveProject(opts);
  }

  /**
   * git 식별. 명시 경로는 즉시 해석(캐시 안 함). 무인자는 1회 캐시하되, **hook cwd 가 바뀌면 재해석**
   * (프로젝트 전환 대응). approve() 가 문맥을 고정하면 그 값을 유지.
   */
  detect(explicitPath?: string): ResolvedProject | null {
    if (explicitPath) return this.resolveAt(explicitPath, undefined);
    const hook = this.readHook();
    // 모호(다중 세션 + 내 세션 id 불명)면 hook cwd 를 신뢰하지 않는다 — 최신 세션을 내 것이라 넘겨짚어
    // 엉뚱한 프로젝트로 귀속하느니, 명시 projectRoot 가 없으면 미해결(null)로 둔다(미귀속 = 안전).
    // preferSessionId(CLAUDE_CODE_SESSION_ID 포함)가 있으면 readContext 가 ambiguous=false 라 정상 경로.
    const ambiguous = hook?.ambiguous === true;
    const hookCwd = ambiguous ? undefined : hook?.cwd;
    if (this.cachedResolve === undefined || hookCwd !== this.lastHookCwd) {
      this.lastHookCwd = hookCwd;
      this.cachedResolve = ambiguous && !this.cfg.projectRoot ? null : this.resolveAt(undefined, hookCwd);
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
    // 토큰-ns 동의 인덱스를 **먼저** 기록(오프라인 Hook/Sender 의 수집 판정 기준 = source of truth).
    // 여기서 실패하면 approve 가 던져 userId-ns 승인도 안 써진다(둘 중 토큰-ns 만 비는 상황 방지).
    if (this.cfg.consentNamespace) {
      setConsent(
        this.cfg.consentNamespace,
        { projectKey: p.projectKey, gitRoot: p.gitRoot, status: 'APPROVED', remoteAtApproval: p.remote },
        { path: this.cfg.consentPath },
      );
    }
    persistApprove(ns, p.gitRoot, { projectKey: p.projectKey, remote: p.remote }, { path: this.cfg.statePath });
    // 명시 경로로 승인했어도 이후 ensureSession(무인자 detect)이 같은 프로젝트를 쓰도록 문맥 고정(②).
    this.cachedResolve = p;
    this.lastHookCwd = this.readHook()?.cwd; // 고정값과 hook 캐시키 정합(승인 직후 재해석으로 덮이지 않게)
    this.lastRegisteredKey = undefined; // 재등록 유도
  }

  /**
   * 연결 해제 — 로컬 승인 삭제 + **새 sessionUuid 로 로테이션**(①). 서버의 세션 바인딩은 null 로 비울 수
   * 없으므로(계약상 no-op), 기존 바운드 세션을 버리고 새 미귀속 세션을 쓴다. 기존 Capture 는 소급 변경 안 함.
   */
  async revoke(p: ResolvedProject): Promise<void> {
    // 토큰-ns 동의 인덱스를 **먼저** 제거 → 오프라인 Hook/Sender 가 즉시 수집 중단(whoami 불필요).
    if (this.cfg.consentNamespace) clearConsent(this.cfg.consentNamespace, p.gitRoot, { path: this.cfg.consentPath });
    this.sessionUuid = newUuid();
    this.rotated = true; // session_id 재채택 금지(미귀속 decouple 유지)
    this.lastRegisteredKey = undefined;
    // userId-ns 원본 승인 삭제(온라인일 때). whoami 실패해도 위에서 수집은 이미 끊겼다.
    const ns = await this.namespace();
    if (ns) persistRevoke(ns, p.gitRoot, { path: this.cfg.statePath });
  }

  /**
   * "나중에" — 이 Claude Code 세션에서만 최초 안내를 보류(SNOOZED). 토큰-ns 인덱스에 세션 키로 기록.
   * 승인(userId-ns)과 무관하며 수집은 여전히 안 한다(미승인 유지). 새 세션/`/clear` 후에는 다시 안내.
   */
  async snooze(p: ResolvedProject): Promise<void> {
    if (!this.cfg.consentNamespace) return;
    setConsent(
      this.cfg.consentNamespace,
      { projectKey: p.projectKey, gitRoot: p.gitRoot, status: 'SNOOZED', remoteAtApproval: p.remote, snoozeSessionId: this.cfg.sessionId ?? null },
      { path: this.cfg.consentPath },
    );
  }

  /**
   * 거절 — 영속 DECLINED. 다음 세션에도 자동 안내·수집 안 함. 명시 approve()(connect_project/CLI)로만 재연결.
   */
  async decline(p: ResolvedProject): Promise<void> {
    if (!this.cfg.consentNamespace) return;
    setConsent(
      this.cfg.consentNamespace,
      { projectKey: p.projectKey, gitRoot: p.gitRoot, status: 'DECLINED', remoteAtApproval: p.remote },
      { path: this.cfg.consentPath },
    );
  }

  /**
   * Capture 에 사용할 sessionUuid 를 돌려준다. 승인 프로젝트가 바뀌었으면 세션을 (재)등록한다.
   * 등록 실패는 삼킨다(서버가 미등록 세션 무시 → Capture 미귀속).
   */
  async ensureSession(): Promise<string> {
    // 세션 정체성(D2): hook session_id 우선, 없으면 config.sessionId 에서 sessionUuid 파생.
    // 이벤트(sessionId 로 태그)와 Capture(sessionUuid)가 같은 세션에서 나와 B 연결이 상관 가능해진다.
    // 로테이션(해제) 상태에서는 재채택하지 않는다(미귀속 decouple 유지).
    if (!this.rotated) {
      // 모호한 문맥의 sessionId 는 채택하지 않는다(엉뚱한 세션 UUID 로 바뀌어 남의 세션에 Capture 가 붙는 것 방지).
      const hook = this.readHook();
      const hookSid = hook && !hook.ambiguous ? (hook.sessionId ?? undefined) : undefined;
      const sidSource = hookSid ?? this.cfg.sessionId;
      if (sidSource) {
        const derived = deriveUuid(sidSource);
        if (derived !== this.sessionUuid) this.sessionUuid = derived;
      }
    }
    const desired = await this.approvedProjectKey();
    const needsRegister = this.lastRegisteredKey !== desired || this.lastRegisteredSessionUuid !== this.sessionUuid;
    if (needsRegister) {
      // 바운드→미귀속 전이(승인 철회·remote 변경 등, revoke 미경유 포함): 기존 세션 바인딩을 비울 수 없으므로
      // 새 sessionUuid 로 로테이션해 미귀속 세션을 새로 쓴다(①).
      if (desired == null && typeof this.lastRegisteredKey === 'string') {
        this.sessionUuid = newUuid();
        this.rotated = true;
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
        this.lastRegisteredSessionUuid = this.sessionUuid;
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
