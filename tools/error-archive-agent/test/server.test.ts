import assert from 'node:assert/strict';
import { test } from 'node:test';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import type { ApiClient } from '../src/apiClient.js';
import { ApiError } from '../src/apiClient.js';
import { createServer } from '../src/server.js';
import type { CaptureContent, CaptureResponse, CreateCaptureRequest } from '../src/types.js';

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
    'diagnose',
  ]);
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

test('API 오류는 isError 결과로 보고된다(코드 포함)', async () => {
  const { client } = fakeClient({
    createCapture: async () => { throw new ApiError(409, 'IDEMPOTENCY_CONFLICT', 'dup'); },
  });
  const mcp = await connect(client);
  const res = (await mcp.callTool({ name: 'capture_start', arguments: { title: 'x' } })) as any;
  assert.equal(res.isError, true);
  assert.match(textOf(res), /IDEMPOTENCY_CONFLICT/);
});
