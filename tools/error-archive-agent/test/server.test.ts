import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import type { ApiClient } from '../src/apiClient.js';
import { ApiError } from '../src/apiClient.js';
import type { FlushResult } from '../src/eventSender.js';
import { idempotencyKeyFor } from '../src/idempotency.js';
import { ProjectContext } from '../src/projectContext.js';
import { createServer } from '../src/server.js';
import type { CaptureContent, CaptureResponse, CreateCaptureRequest, UpdateAttemptRequest, UpdateSolutionRequest } from '../src/types.js';

const AUUID = '00000000-0000-4000-8000-000000000000';

/** 호출을 기록하는 가짜 ApiClient — 네트워크 없이 서버 배선을 검증한다. */
function fakeClient(overrides: Partial<ApiClient> = {}) {
  const calls: { createCapture?: CreateCaptureRequest } = {};
  const base: ApiClient = {
    whoami: async () => ({ userId: 7, connectionId: 'conn-7', scopes: ['capture:write', 'artifact:write'] }),
    createCapture: async (req: CreateCaptureRequest): Promise<CaptureResponse> => {
      calls.createCapture = req;
      return {
        captureUuid: req.captureUuid,
        status: 'OPEN',
        revision: 0,
        title: req.title ?? null,
        project: req.project ?? null,
        content: req.content,
        createdAt: 't',
        updatedAt: 't',
        completedAt: null,
      };
    },
    registerSession: async () => { throw new Error('unused'); },
    getCapture: async () => { throw new Error('unused'); },
    listOpenCaptures: async () => ({ items: [] }),
    patchCapture: async () => { throw new Error('unused'); },
    addAttempt: async () => { throw new Error('unused'); },
    updateAttempt: async () => { throw new Error('unused'); },
    addSolution: async () => { throw new Error('unused'); },
    uploadArtifact: async () => { throw new Error('unused'); },
    completeCapture: async () => { throw new Error('unused'); },
    searchCases: async () => { throw new Error('unused'); },
    getCase: async () => { throw new Error('unused'); },
    ingestEvents: async () => { throw new Error('unused'); },
    listEvents: async () => { throw new Error('unused'); },
    linkEvents: async () => { throw new Error('unused'); },
    ...overrides,
  };
  return { client: base, calls };
}

async function connect(
  api: ApiClient,
  flushEvents?: () => Promise<FlushResult>,
  project?: { currentSessionId(): string | undefined },
  sealFlush?: () => Promise<FlushResult>,
  pendingForSession?: (sessionId: string) => number,
): Promise<Client> {
  const [clientT, serverT] = InMemoryTransport.createLinkedPair();
  const server = createServer(api, project as never, flushEvents, sealFlush, pendingForSession);
  await server.connect(serverT);
  const client = new Client({ name: 'test', version: '0' });
  await client.connect(clientT);
  return client;
}

function textOf(result: { content: Array<{ type: string; text?: string }> }): string {
  return result.content.filter((c) => c.type === 'text').map((c) => c.text ?? '').join('');
}

test('모든 기대 도구가 등록돼 있다', async () => {
  const { client } = fakeClient();
  const mcp = await connect(client);
  const names = (await mcp.listTools()).tools.map((t) => t.name).sort();
  assert.deepEqual(names, [
    'capture_add_attempt',
    'capture_add_solution',
    'capture_attach_evidence',
    'capture_complete',
    'capture_get',
    'capture_link_events',
    'capture_list_events',
    'capture_list_open',
    'capture_start',
    'capture_update',
    'capture_update_attempt',
    'capture_update_solution',
    'connect_project',
    'connection_status',
    'diagnose',
    'find_cases',
    'get_case',
  ]);
});

test('capture_update_attempt 는 수정한 evidenceRefs 로 PATCH 한다', async () => {
  let captured: { uuid: string; clientAttemptId: string; req: UpdateAttemptRequest } | null = null;
  const { client } = fakeClient({
    updateAttempt: async (uuid, clientAttemptId, req) => {
      captured = { uuid, clientAttemptId, req };
      return {
        clientAttemptId, orderIndex: 0, result: req.result,
        attemptType: req.attemptType ?? null, detail: req.detail, createdAt: 't', updatedAt: 't',
      };
    },
  });
  const mcp = await connect(client);
  await mcp.callTool({
    name: 'capture_update_attempt',
    arguments: { captureUuid: AUUID, clientAttemptId: 'a1', result: 'FAILED', title: 'repro', evidenceRefs: ['log-002'] },
  });
  assert.ok(captured);
  assert.equal(captured!.clientAttemptId, 'a1');
  assert.equal(captured!.req.result, 'FAILED');
  assert.deepEqual(captured!.req.detail.evidenceRefs, ['log-002']);
});

test('capture_update_solution 은 clientSolutionId 로 PATCH 하고 verification 을 교체한다', async () => {
  let captured: { uuid: string; clientSolutionId: string; req: UpdateSolutionRequest } | null = null;
  const { client } = fakeClient({
    updateSolution: async (uuid, clientSolutionId, req) => {
      captured = { uuid, clientSolutionId, req };
      return { clientSolutionId, title: req.title, content: req.content, createdAt: 't' };
    },
  });
  const mcp = await connect(client);
  await mcp.callTool({
    name: 'capture_update_solution',
    arguments: {
      captureUuid: AUUID, clientSolutionId: 's1', title: '정정',
      clientAttemptIds: ['a1'],
      verificationResult: 'PASSED', verificationExecuted: true, verificationCommand: './gradlew test',
      verificationTestId: 'CartServiceTest#total', verificationRanAt: '2026-11-12T00:00:00Z', verificationCommitSha: 'abc123',
    },
  });
  assert.ok(captured);
  assert.equal(captured!.clientSolutionId, 's1');
  assert.equal(captured!.req.title, '정정');
  assert.deepEqual(captured!.req.content.clientAttemptIds, ['a1']);
  assert.equal(captured!.req.content.verification?.result, 'PASSED');
  assert.equal(captured!.req.content.verification?.executed, true);
  // #172 보강: testId/ranAt/commitSha 도 도구→매퍼를 통해 전달되는지(전체 교체 시 유실 방지).
  assert.equal(captured!.req.content.verification?.testId, 'CartServiceTest#total');
  assert.equal(captured!.req.content.verification?.ranAt, '2026-11-12T00:00:00Z');
  assert.equal(captured!.req.content.verification?.commitSha, 'abc123');
});

test('diagnose 는 whoami 결과를 반환한다', async () => {
  const { client } = fakeClient();
  const mcp = await connect(client);
  const res = (await mcp.callTool({ name: 'diagnose', arguments: {} })) as any;
  const body = JSON.parse(textOf(res));
  assert.equal(body.userId, 7);
  assert.deepEqual(body.scopes, ['capture:write', 'artifact:write']);
});

test('capture_start 는 captureUuid 를 생성하고 서사를 구조화해 전달한다', async () => {
  const { client, calls } = fakeClient();
  const mcp = await connect(client);
  const res = (await mcp.callTool({
    name: 'capture_start',
    arguments: { title: 'NPE', symptom: 'npe', observedFacts: ['log'], reproductionSteps: ['run'] },
  })) as any;
  const body = JSON.parse(textOf(res));
  assert.match(body.captureUuid, /^[0-9a-f-]{36}$/);
  // 서버로 보낸 content 가 구조화됐는지
  const sent = calls.createCapture!;
  assert.equal(sent.captureUuid, body.captureUuid);
  const content = sent.content as CaptureContent;
  assert.equal(content.symptom, 'npe');
  assert.deepEqual(content.observedFacts, ['log']);
  assert.deepEqual(content.reproduction, { steps: ['run'], executed: false });
});

test('capture_start 는 captureUuid 에서 파생한 결정론적 멱등키를 보낸다', async () => {
  let sentUuid: string | undefined;
  let sentKey: string | undefined;
  const { client } = fakeClient({
    createCapture: async (req: CreateCaptureRequest, key?: string): Promise<CaptureResponse> => {
      sentUuid = req.captureUuid;
      sentKey = key;
      return {
        captureUuid: req.captureUuid,
        status: 'OPEN',
        revision: 0,
        title: req.title ?? null,
        project: req.project ?? null,
        content: req.content,
        createdAt: 't',
        updatedAt: 't',
        completedAt: null,
      };
    },
  });
  const mcp = await connect(client);
  await mcp.callTool({ name: 'capture_start', arguments: { title: 'x' } });
  assert.ok(sentUuid);
  assert.equal(sentKey, idempotencyKeyFor('capture.create', sentUuid!));
});

test('capture_attach_evidence 기본 clientArtifactId 는 파일경로 기반(동명 다른 파일 구분)', async () => {
  const ids: string[] = [];
  const { client } = fakeClient({
    uploadArtifact: async (_uuid: string, clientArtifactId: string) => {
      ids.push(clientArtifactId);
      return {
        artifact: {
          clientArtifactId,
          sha256: 'h',
          contentType: 'text/plain',
          sizeBytes: 1,
          fileName: 'error.log',
          status: 'STORED',
          createdAt: 't',
        },
        deduplicated: false,
      };
    },
  });
  const mcp = await connect(client);
  await mcp.callTool({ name: 'capture_attach_evidence', arguments: { captureUuid: AUUID, filePath: 'a/error.log' } });
  await mcp.callTool({ name: 'capture_attach_evidence', arguments: { captureUuid: AUUID, filePath: 'b/error.log' } });
  assert.equal(ids.length, 2);
  assert.ok(ids[0]!.startsWith('error.log-'));
  assert.ok(ids[1]!.startsWith('error.log-'));
  assert.notEqual(ids[0], ids[1]);
});

test('API 오류는 isError 결과로 보고된다(코드 포함)', async () => {
  const { client } = fakeClient({
    createCapture: async () => { throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'dup'); },
  });
  const mcp = await connect(client);
  const res = (await mcp.callTool({ name: 'capture_start', arguments: { title: 'x' } })) as any;
  assert.equal(res.isError, true);
  assert.match(textOf(res), /IDEMPOTENCY_CONFLICT/);
});

test('capture_list_events: 조회 전 flush 하고 UNCLASSIFIED 를 sessionId 로 조회', async () => {
  let listed: { status?: string; sessionId?: string } | null = null;
  const { client } = fakeClient({
    listEvents: async (status, sessionId) => {
      listed = { status, sessionId };
      return { items: [{ clientEventId: 'E1', eventType: 'PostToolUse:Bash', sessionId: 's1', observedAt: 't', toolCallId: null, truncated: false, status: 'UNCLASSIFIED', redactedPayload: {} }] };
    },
  });
  let flushed = false;
  const mcp = await connect(client, async () => {
    flushed = true;
    return { sent: 2, rejected: 0, dropped: 0, pending: 3 };
  });
  const res = (await mcp.callTool({ name: 'capture_list_events', arguments: { sessionId: 's1' } })) as {
    content: Array<{ type: string; text?: string }>;
  };
  assert.equal(flushed, true); // 조회 전 flush
  assert.equal(listed!.status, 'UNCLASSIFIED');
  assert.equal(listed!.sessionId, 's1');
  // flush 결과가 응답 sync 로 전달돼 미전송(pending) 건수를 AI 가 알 수 있다.
  const payload = JSON.parse(res.content[0].text ?? '{}');
  assert.equal(payload.sync.pending, 3);
  assert.equal(payload.sync.rejected, 0);
  assert.ok(Array.isArray(payload.items));
});

test('capture_list_events: cursor 를 서버로 전달하고 nextCursor/hasNext 를 응답에 통과', async () => {
  let gotCursor: number | undefined;
  const { client } = fakeClient({
    listEvents: async (_status, _sessionId, _limit, cursor) => {
      gotCursor = cursor;
      return {
        items: [{ clientEventId: 'E1', eventType: 'PostToolUse:Bash', sessionId: 's1', observedAt: 't', toolCallId: null, truncated: false, status: 'UNCLASSIFIED', redactedPayload: {} }],
        nextCursor: 40,
        hasNext: true,
      };
    },
  });
  const mcp = await connect(client);
  const res = (await mcp.callTool({ name: 'capture_list_events', arguments: { cursor: 42 } })) as {
    content: Array<{ type: string; text?: string }>;
  };
  assert.equal(gotCursor, 42, 'cursor 가 서버 조회로 전달됨');
  const payload = JSON.parse(res.content[0].text ?? '{}');
  assert.equal(payload.nextCursor, 40); // 다음 페이지 cursor 통과
  assert.equal(payload.hasNext, true);
});

test('capture_list_events: sessionId 생략 → 현재 세션 id 자동 주입(주)', async () => {
  let listed: { sessionId?: string } | null = null;
  const { client } = fakeClient({
    listEvents: async (_status, sessionId) => {
      listed = { sessionId };
      return { items: [] };
    },
  });
  const project = { currentSessionId: () => 'raw-current-sid' };
  const mcp = await connect(client, undefined, project);
  await mcp.callTool({ name: 'capture_list_events', arguments: {} }); // sessionId 생략
  assert.equal(listed!.sessionId, 'raw-current-sid'); // 커넥터가 아는 원본 세션 id 로 자동 좁힘
});

test('capture_list_events: 명시 sessionId 는 자동 주입을 override', async () => {
  let listed: { sessionId?: string } | null = null;
  const { client } = fakeClient({
    listEvents: async (_status, sessionId) => {
      listed = { sessionId };
      return { items: [] };
    },
  });
  const project = { currentSessionId: () => 'raw-current-sid' };
  const mcp = await connect(client, undefined, project);
  await mcp.callTool({ name: 'capture_list_events', arguments: { sessionId: 'explicit' } });
  assert.equal(listed!.sessionId, 'explicit');
});

test('capture_link_events: 선택 이벤트만 링크 호출', async () => {
  let captured: { uuid: string; ids: string[] } | null = null;
  const { client } = fakeClient({
    linkEvents: async (uuid, ids) => {
      captured = { uuid, ids };
      return { linked: ids, skipped: [] };
    },
  });
  const mcp = await connect(client);
  await mcp.callTool({ name: 'capture_link_events', arguments: { captureUuid: AUUID, clientEventIds: ['E1', 'E2'] } });
  assert.equal(captured!.uuid, AUUID);
  assert.deepEqual(captured!.ids, ['E1', 'E2']);
});

test('capture_start(Phase3): 미승인 프로젝트면 생성 차단, 승인 후 생성됨', async () => {
  const { client: api, calls } = fakeClient();
  const dir = mkdtempSync(join(tmpdir(), 'ea-srv-consent-'));
  const resolved = { gitRoot: '/dev/x', remote: 'github.com/o/x', projectKey: 'github.com/o/x', trust: 'config' as const };
  const pc = new ProjectContext(api, {
    baseUrl: 'https://api',
    statePath: join(dir, 'p.json'),
    consentNamespace: 'cns',
    consentPath: join(dir, 'c.json'),
    resolve: () => resolved,
  });
  const [clientT, serverT] = InMemoryTransport.createLinkedPair();
  const server = createServer(api, pc);
  await server.connect(serverT);
  const mcp = new Client({ name: 'test', version: '0' });
  await mcp.connect(clientT);
  try {
    // 미승인 → 생성 차단(needsApproval), createCapture 미호출.
    const blocked = JSON.parse(textOf((await mcp.callTool({ name: 'capture_start', arguments: { title: 't' } })) as never));
    assert.equal(blocked.created, false);
    assert.equal(blocked.needsApproval, true);
    assert.equal(calls.createCapture, undefined, '미승인이면 createCapture 호출 안 함');

    // 승인 후 → 생성됨(captureUuid 반환).
    await pc.approve(resolved);
    const made = JSON.parse(textOf((await mcp.callTool({ name: 'capture_start', arguments: { title: 't' } })) as never));
    assert.ok(made.captureUuid, '승인 후엔 captureUuid 반환');
    assert.ok(calls.createCapture, '승인 후엔 createCapture 호출');
  } finally {
    await mcp.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('connect_project: 승인만으로 세션을 프로젝트에 즉시 등록(귀속) — capture_start 불필요', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'ea-srv-connect-'));
  const resolved = { gitRoot: '/dev/x', remote: 'github.com/o/x', projectKey: 'github.com/o/x', trust: 'config' as const };
  let registered: { sessionUuid: string; projectKey?: string } | null = null;
  const { client: api } = fakeClient({
    registerSession: async (req) => {
      registered = { sessionUuid: req.sessionUuid, projectKey: req.projectKey };
      return {
        sessionUuid: req.sessionUuid, connectionId: 'conn-7', clientKind: req.clientKind,
        clientVersion: null, clientSessionId: req.clientSessionId, startedAt: 't',
      };
    },
  });
  const pc = new ProjectContext(api, {
    baseUrl: 'https://api',
    statePath: join(dir, 'p.json'),
    consentNamespace: 'cns',
    consentPath: join(dir, 'c.json'),
    resolve: () => resolved,
  });
  const [clientT, serverT] = InMemoryTransport.createLinkedPair();
  const server = createServer(api, pc);
  await server.connect(serverT);
  const mcp = new Client({ name: 'test', version: '0' });
  await mcp.connect(clientT);
  try {
    // 사전 승인(기존 연결 상태) — approve 만으로는 아직 서버 세션 등록 전.
    await pc.approve(resolved);
    assert.equal(registered, null, 'approve 만으로는 아직 registerSession 호출 안 됨');

    // connect_project 호출만으로(= capture_start 없이) 세션이 프로젝트에 등록(귀속)돼야 한다.
    const res = JSON.parse(textOf((await mcp.callTool({ name: 'connect_project', arguments: {} })) as never));
    assert.equal(res.connected, true);
    assert.ok(res.sessionUuid, 'connect_project 가 등록된 sessionUuid 를 반환');
    assert.ok(registered, 'connect_project 호출만으로 registerSession 발생(즉시 귀속)');
    assert.equal(registered!.projectKey, 'github.com/o/x', '승인한 프로젝트로 귀속');
  } finally {
    await mcp.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

// ── PR-5: 봉인 완결성(B+D) — 미전송/미확인 근거 있으면 봉인 보류, force 로 강행 ──────────
const fr = (pending: number): FlushResult => ({ sent: 0, rejected: 0, dropped: 0, pending });
const sealResp = (): CaptureResponse => ({
  captureUuid: AUUID, status: 'READY_FOR_REVIEW', revision: 1, title: null, project: null,
  content: { observedFacts: [], hypotheses: [], unknowns: [] }, createdAt: 't', updatedAt: 't', completedAt: 't',
});
const evSummary = (id: string, sessionId: string) => ({
  clientEventId: id, eventType: 'PostToolUse:Bash', sessionId, observedAt: 't', toolCallId: null,
  truncated: false, status: 'UNCLASSIFIED', redactedPayload: {},
});

test('(PR-5) capture_complete: 미전송 근거(localPending>0) 있으면 봉인 보류 + completeCapture 미호출', async () => {
  let completeCalled = false;
  let flushed = false;
  const { client } = fakeClient({
    listEvents: async () => ({ items: [] }), // 서버 미확인분 없음
    completeCapture: async () => { completeCalled = true; return sealResp(); },
  });
  const mcp = await connect(
    client, undefined, { currentSessionId: () => 's1' },
    async () => { flushed = true; return fr(2); }, // sealFlush
    () => 2, // pendingForSession → 2 미전송
  );
  const payload = JSON.parse(textOf((await mcp.callTool({ name: 'capture_complete', arguments: { captureUuid: AUUID } })) as never));
  assert.equal(flushed, true, '봉인 전 전량 flush 선행');
  assert.equal(payload.sealed, false);
  assert.equal(payload.reason, 'EVIDENCE_PENDING');
  assert.equal(payload.localPending, 2);
  assert.equal(completeCalled, false);
});

test('(PR-5) capture_complete: 서버에 미확인(unseen) 이벤트 있으면 봉인 보류', async () => {
  let completeCalled = false;
  const { client } = fakeClient({
    listEvents: async () => ({ items: [evSummary('E9', 's1')] }), // AI 가 list 로 본 적 없음 → unseen
    completeCapture: async () => { completeCalled = true; return sealResp(); },
  });
  const mcp = await connect(client, undefined, { currentSessionId: () => 's1' }, async () => fr(0), () => 0);
  const payload = JSON.parse(textOf((await mcp.callTool({ name: 'capture_complete', arguments: { captureUuid: AUUID } })) as never));
  assert.equal(payload.sealed, false);
  assert.equal(payload.unseenOnServer, 1);
  assert.equal(completeCalled, false);
});

test('(PR-5) capture_complete: 본 이벤트뿐이고 미전송 0 이면 봉인', async () => {
  let completeCalled = false;
  const { client } = fakeClient({
    listEvents: async () => ({ items: [evSummary('E9', 's1')] }),
    completeCapture: async () => { completeCalled = true; return sealResp(); },
  });
  const mcp = await connect(client, undefined, { currentSessionId: () => 's1' }, async () => fr(0), () => 0);
  await mcp.callTool({ name: 'capture_list_events', arguments: {} }); // E9 를 봄(seen 기록)
  const payload = JSON.parse(textOf((await mcp.callTool({ name: 'capture_complete', arguments: { captureUuid: AUUID } })) as never));
  assert.equal(payload.sealed, true);
  assert.equal(completeCalled, true);
});

test('(PR-5) capture_complete: force=true 면 미전송/미확인 있어도 봉인', async () => {
  let completeCalled = false;
  const { client } = fakeClient({
    listEvents: async () => ({ items: [evSummary('E9', 's1')] }), // unseen 존재
    completeCapture: async () => { completeCalled = true; return sealResp(); },
  });
  const mcp = await connect(client, undefined, { currentSessionId: () => 's1' }, async () => fr(0), () => 5); // pending 5 도
  const payload = JSON.parse(textOf((await mcp.callTool({ name: 'capture_complete', arguments: { captureUuid: AUUID, force: true } })) as never));
  assert.equal(payload.sealed, true);
  assert.equal(completeCalled, true);
});
