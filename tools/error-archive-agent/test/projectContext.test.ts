import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { ApiClient } from '../src/apiClient.js';
import type { ResolvedProject } from '../src/project.js';
import { ProjectContext } from '../src/projectContext.js';
import type { ResolvedSessionContext } from '../src/sessionContext.js';

const PROJECT: ResolvedProject = {
  gitRoot: '/dev/checkout', remote: 'github.com/org/checkout', projectKey: 'github.com/org/checkout', trust: 'config',
};

interface RegCall { projectKey: string | null; sessionUuid: string; key?: string }

function makeClient(calls: RegCall[], opts: { failTimes?: number } = {}): ApiClient {
  let attempts = 0;
  return {
    whoami: async () => ({ userId: 7, connectionId: 'c', scopes: [] }),
    registerSession: async (req: { projectKey?: string | null; sessionUuid: string; clientKind: string; clientSessionId: string }, key?: string) => {
      attempts += 1;
      if (opts.failTimes && attempts <= opts.failTimes) throw new Error('network');
      calls.push({ projectKey: req.projectKey ?? null, sessionUuid: req.sessionUuid, key });
      return { sessionUuid: req.sessionUuid, connectionId: 'c', clientKind: req.clientKind, clientVersion: null, clientSessionId: req.clientSessionId, startedAt: 't' };
    },
  } as unknown as ApiClient;
}

function makeCtx(client: ApiClient): { c: ProjectContext; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-ctx-'));
  const c = new ProjectContext(client, { baseUrl: 'https://api', statePath: join(dir, 'p.json'), resolve: () => PROJECT });
  return { c, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('미승인 → projectKey 없이 세션 등록', async () => {
  const calls: RegCall[] = [];
  const { c, cleanup } = makeCtx(makeClient(calls));
  try {
    await c.ensureSession();
    assert.equal(calls.length, 1);
    assert.equal(calls[0]!.projectKey, null);
  } finally { cleanup(); }
});

test('승인 → projectKey 포함 등록', async () => {
  const calls: RegCall[] = [];
  const { c, cleanup } = makeCtx(makeClient(calls));
  try {
    await c.approve(PROJECT);
    await c.ensureSession();
    assert.equal(calls.at(-1)!.projectKey, 'github.com/org/checkout');
  } finally { cleanup(); }
});

test('변경 없으면 재등록 생략', async () => {
  const calls: RegCall[] = [];
  const { c, cleanup } = makeCtx(makeClient(calls));
  try {
    await c.approve(PROJECT);
    await c.ensureSession();
    await c.ensureSession();
    assert.equal(calls.length, 1);
  } finally { cleanup(); }
});

test('승인 상태 변경 → 새 Idempotency-Key 로 재등록', async () => {
  const calls: RegCall[] = [];
  const { c, cleanup } = makeCtx(makeClient(calls));
  try {
    await c.ensureSession(); // 미승인
    await c.approve(PROJECT); // 승인으로 변경
    await c.ensureSession();
    assert.equal(calls.length, 2);
    assert.notEqual(calls[0]!.key, calls[1]!.key); // 새 Idempotency-Key
    assert.equal(calls[0]!.projectKey, null);
    assert.equal(calls[1]!.projectKey, 'github.com/org/checkout');
  } finally { cleanup(); }
});

test('revoke → 새 sessionUuid 로 미귀속 세션 로테이션', async () => {
  const calls: RegCall[] = [];
  const { c, cleanup } = makeCtx(makeClient(calls));
  try {
    await c.approve(PROJECT);
    const s1 = await c.ensureSession();
    await c.revoke(PROJECT);
    const s2 = await c.ensureSession();
    assert.notEqual(s1, s2); // sessionUuid 로테이션
    assert.equal(calls.at(-1)!.projectKey, null); // 미귀속
    assert.equal(calls.at(-1)!.sessionUuid, s2);
  } finally { cleanup(); }
});

test('등록 실패 → sessionUuid 반환·stale 미상속(다음 호출 재시도)', async () => {
  const calls: RegCall[] = [];
  const client = makeClient(calls, { failTimes: 1 });
  const { c, cleanup } = makeCtx(client);
  try {
    await c.approve(PROJECT);
    const s = await c.ensureSession(); // 첫 등록 실패(삼킴)
    assert.ok(s.length > 0);
    assert.equal(calls.length, 0); // 실패라 미기록
    await c.ensureSession(); // lastRegisteredKey 미갱신 → 재시도
    assert.equal(calls.length, 1);
    assert.equal(calls[0]!.projectKey, 'github.com/org/checkout');
  } finally { cleanup(); }
});

// ── G: Hook 세션 문맥 배선 ──────────────────────────────────────────────────────

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 경로별 가짜 프로젝트(gitRoot=경로). */
function projFor(path: string): ResolvedProject {
  return { gitRoot: path, remote: `github.com/x${path}`, projectKey: `key:${path}`, trust: 'hook' };
}

/** readHookContext·resolve 를 주입해 hook cwd→프로젝트 매핑을 테스트. */
function makeHookCtx(
  client: ApiClient,
  holder: { current: { cwd: string; sessionId: string | null } | null },
): { c: ProjectContext; cleanup: () => void } {
  const dir = mkdtempSync(join(tmpdir(), 'ea-hookctx-'));
  const c = new ProjectContext(client, {
    baseUrl: 'https://api',
    statePath: join(dir, 'p.json'),
    readHookContext: (): ResolvedSessionContext | null =>
      holder.current ? { cwd: holder.current.cwd, sessionId: holder.current.sessionId, ambiguous: false } : null,
    resolve: (opts) => {
      const path = opts.explicitPath ?? opts.hookPath ?? opts.configRoot;
      return path ? projFor(path) : null; // cwd 폴백은 프로젝트 없음
    },
  });
  return { c, cleanup: () => rmSync(dir, { recursive: true, force: true }) };
}

test('hook cwd 로 프로젝트 해석 → 승인 시 projectKey 상속', async () => {
  const calls: RegCall[] = [];
  const holder = { current: { cwd: '/dev/hooked', sessionId: 'hs' } as { cwd: string; sessionId: string | null } | null };
  const { c, cleanup } = makeHookCtx(makeClient(calls), holder);
  try {
    const p = c.detect();
    assert.equal(p?.projectKey, 'key:/dev/hooked'); // hook cwd 가 해석됨
    await c.approve(p!);
    await c.ensureSession();
    assert.equal(calls.at(-1)!.projectKey, 'key:/dev/hooked');
  } finally { cleanup(); }
});

test('hook cwd 변경(프로젝트 전환) → 캐시 무효화·재해석', async () => {
  const calls: RegCall[] = [];
  const holder = { current: { cwd: '/dev/h1', sessionId: 'hs' } as { cwd: string; sessionId: string | null } | null };
  const { c, cleanup } = makeHookCtx(makeClient(calls), holder);
  try {
    const p1 = c.detect();
    await c.approve(p1!);
    await c.ensureSession();
    assert.equal(calls.at(-1)!.projectKey, 'key:/dev/h1');
    // 작업 폴더 전환 → 다른(미승인) 프로젝트.
    holder.current = { cwd: '/dev/h2', sessionId: 'hs' };
    assert.equal(c.detect()?.projectKey, 'key:/dev/h2'); // 재해석됨
    await c.ensureSession();
    assert.equal(calls.at(-1)!.projectKey, null); // 미승인 프로젝트 → 미귀속
  } finally { cleanup(); }
});

test('session_id → sessionUuid 결정적 파생(같은 세션=같은 uuid)', async () => {
  const callsA: RegCall[] = [];
  const callsB: RegCall[] = [];
  const hA = { current: { cwd: '/dev/a', sessionId: 'session-xyz' } as { cwd: string; sessionId: string | null } | null };
  const hB = { current: { cwd: '/dev/b', sessionId: 'session-xyz' } as { cwd: string; sessionId: string | null } | null };
  const a = makeHookCtx(makeClient(callsA), hA);
  const b = makeHookCtx(makeClient(callsB), hB);
  try {
    const sA = await a.c.ensureSession();
    const sB = await b.c.ensureSession();
    assert.match(sA, UUID_RE);
    assert.equal(sA, sB); // 같은 hook session_id → 같은 sessionUuid(이벤트와 정합)
    assert.equal(callsA.at(-1)!.sessionUuid, sA);
  } finally { a.cleanup(); b.cleanup(); }
});

test('다른 session_id → 다른 sessionUuid', async () => {
  const h1 = { current: { cwd: '/dev/a', sessionId: 's-1' } as { cwd: string; sessionId: string | null } | null };
  const h2 = { current: { cwd: '/dev/a', sessionId: 's-2' } as { cwd: string; sessionId: string | null } | null };
  const c1 = makeHookCtx(makeClient([]), h1);
  const c2 = makeHookCtx(makeClient([]), h2);
  try {
    assert.notEqual(await c1.c.ensureSession(), await c2.c.ensureSession());
  } finally { c1.cleanup(); c2.cleanup(); }
});

test('미승인 hook cwd → projectKey 미전송(승인 게이트 유지)', async () => {
  const calls: RegCall[] = [];
  const holder = { current: { cwd: '/dev/hooked', sessionId: 'hs' } as { cwd: string; sessionId: string | null } | null };
  const { c, cleanup } = makeHookCtx(makeClient(calls), holder);
  try {
    await c.ensureSession(); // 승인 없이
    assert.equal(calls.at(-1)!.projectKey, null);
  } finally { cleanup(); }
});
