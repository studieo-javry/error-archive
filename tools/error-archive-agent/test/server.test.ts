import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import type { ApiClient } from '../src/apiClient.js';
import { ApiError } from '../src/apiClient.js';
import { idempotencyKeyFor } from '../src/idempotency.js';
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
    patchCapture: async () => { throw new Error('unused'); },
    addAttempt: async () => { throw new Error('unused'); },
    updateAttempt: async () => { throw new Error('unused'); },
    addSolution: async () => { throw new Error('unused'); },
    uploadArtifact: async () => { throw new Error('unused'); },
    completeCapture: async () => { throw new Error('unused'); },
    ...overrides,
  };
  return { client: base, calls };
}

async function connect(api: ApiClient): Promise<Client> {
  const [clientT, serverT] = InMemoryTransport.createLinkedPair();
  const server = createServer(api);
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
    'capture_start',
    'capture_update',
    'capture_update_attempt',
    'capture_update_solution',
    'diagnose',
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
