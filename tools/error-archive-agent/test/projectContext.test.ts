import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import type { ApiClient } from '../src/apiClient.js';
import type { ResolvedProject } from '../src/project.js';
import { ProjectContext } from '../src/projectContext.js';

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
