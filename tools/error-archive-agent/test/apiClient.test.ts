import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ApiError, createApiClient, type FetchLike } from '../src/apiClient.js';
import type { AgentConfig } from '../src/config.js';

const config: AgentConfig = { baseUrl: 'https://api.test', token: 'eaa_secret' };

interface Captured {
  url: string;
  init: RequestInit | undefined;
}

/** 응답을 정해두고 요청을 기록하는 fake fetch. */
function fakeFetch(status: number, body: unknown): { fetch: FetchLike; calls: Captured[] } {
  const calls: Captured[] = [];
  const fetch: FetchLike = async (url, init) => {
    calls.push({ url, init });
    const text = typeof body === 'string' ? body : JSON.stringify(body);
    return new Response(text, { status });
  };
  return { fetch, calls };
}

function header(init: RequestInit | undefined, name: string): string | undefined {
  const h = init?.headers as Record<string, string> | undefined;
  return h?.[name];
}

test('whoami: Bearer 토큰과 경로를 실어 보내고 응답을 파싱한다', async () => {
  const { fetch, calls } = fakeFetch(200, { userId: 42, connectionId: 'conn-1', scopes: ['capture:write'] });
  const me = await createApiClient(config, fetch).whoami();
  assert.equal(calls[0]?.url, 'https://api.test/api/v1/agent/me');
  assert.equal(header(calls[0]?.init, 'Authorization'), 'Bearer eaa_secret');
  assert.equal(me.userId, 42);
  assert.deepEqual(me.scopes, ['capture:write']);
});

test('client 정보가 주어지면 모든 요청에 X-Agent-* 설치 헤더를 싣는다', async () => {
  const { fetch, calls } = fakeFetch(200, { userId: 1, connectionId: 'c', scopes: [] });
  await createApiClient(config, fetch, {
    kind: 'claude-code', version: '1.2.3', installId: 'inst-xyz', installLabel: '내 맥북',
  }).whoami();
  const init = calls[0]?.init;
  assert.equal(header(init, 'X-Agent-Client-Kind'), 'claude-code');
  assert.equal(header(init, 'X-Agent-Client-Version'), '1.2.3');
  assert.equal(header(init, 'X-Agent-Install-Id'), 'inst-xyz');
  assert.equal(header(init, 'X-Agent-Install-Label'), '내 맥북');
});

test('client 정보가 없으면 X-Agent-* 헤더를 싣지 않는다(후방호환)', async () => {
  const { fetch, calls } = fakeFetch(200, { userId: 1, connectionId: 'c', scopes: [] });
  await createApiClient(config, fetch).whoami();
  assert.equal(header(calls[0]?.init, 'X-Agent-Install-Id'), undefined);
});

test('installLabel 이 없으면 그 헤더만 생략한다(opt-out)', async () => {
  const { fetch, calls } = fakeFetch(200, { userId: 1, connectionId: 'c', scopes: [] });
  await createApiClient(config, fetch, { kind: 'claude-code', installId: 'i' }).whoami();
  assert.equal(header(calls[0]?.init, 'X-Agent-Install-Id'), 'i');
  assert.equal(header(calls[0]?.init, 'X-Agent-Install-Label'), undefined);
});

test('createCapture: POST + Idempotency-Key + JSON 본문', async () => {
  const { fetch, calls } = fakeFetch(201, { captureUuid: 'u1', status: 'OPEN', revision: 0, content: {} });
  await createApiClient(config, fetch).createCapture(
    { captureUuid: 'u1', content: { observedFacts: [], hypotheses: [], unknowns: [] } },
    'idem-123',
  );
  const init = calls[0]?.init;
  assert.equal(init?.method, 'POST');
  assert.equal(header(init, 'Idempotency-Key'), 'idem-123');
  assert.equal(header(init, 'Content-Type'), 'application/json');
  assert.match(String(init?.body), /"captureUuid":"u1"/);
});

test('Idempotency-Key 미지정이면 자동 생성한다', async () => {
  const { fetch, calls } = fakeFetch(201, { captureUuid: 'u1', status: 'OPEN', revision: 0, content: {} });
  await createApiClient(config, fetch).createCapture({
    captureUuid: 'u1',
    content: { observedFacts: [], hypotheses: [], unknowns: [] },
  });
  assert.ok(header(calls[0]?.init, 'Idempotency-Key'));
});

test('비 2xx 는 problem detail code 를 실은 ApiError', async () => {
  const { fetch } = fakeFetch(409, { code: 'REVISION_CONFLICT', detail: 'revision mismatch' });
  await assert.rejects(
    () => createApiClient(config, fetch).patchCapture('u1', { content: { observedFacts: [], hypotheses: [], unknowns: [] } }),
    (err: unknown) => err instanceof ApiError && err.status === 409 && err.code === 'REVISION_CONFLICT',
  );
});

test('401 은 토큰을 마스킹한 ApiError(원문 노출 금지)', async () => {
  const { fetch } = fakeFetch(401, { code: 'UNAUTHORIZED' });
  await assert.rejects(
    () => createApiClient(config, fetch).whoami(),
    (err: unknown) => err instanceof ApiError && err.status === 401 && !err.message.includes('eaa_secret'),
  );
});

test('409 CONCURRENT_REQUEST 는 같은 키로 한 번 재시도한다', async () => {
  const responses = [
    { status: 409, body: { code: 'CONCURRENT_REQUEST', detail: 'in flight' } },
    { status: 201, body: { captureUuid: 'u1', status: 'OPEN', revision: 0, content: {} } },
  ];
  const calls: Captured[] = [];
  const fetch: FetchLike = async (url, init) => {
    calls.push({ url, init });
    const r = responses.shift()!;
    return new Response(JSON.stringify(r.body), { status: r.status });
  };
  const created = await createApiClient(config, fetch).createCapture(
    { captureUuid: 'u1', content: { observedFacts: [], hypotheses: [], unknowns: [] } },
    'idem-stable',
  );
  assert.equal(created.captureUuid, 'u1');
  assert.equal(calls.length, 2);
  // 재시도는 같은 멱등키를 그대로 보내야 중복 생성이 방지된다.
  assert.equal(header(calls[0]?.init, 'Idempotency-Key'), 'idem-stable');
  assert.equal(header(calls[1]?.init, 'Idempotency-Key'), 'idem-stable');
});

test('CONCURRENT_REQUEST 가 계속되면 1회 재시도 후 ApiError', async () => {
  const { fetch } = fakeFetch(409, { code: 'CONCURRENT_REQUEST', detail: 'still in flight' });
  await assert.rejects(
    () => createApiClient(config, fetch).createCapture(
      { captureUuid: 'u1', content: { observedFacts: [], hypotheses: [], unknowns: [] } },
      'idem-stable',
    ),
    (err: unknown) => err instanceof ApiError && err.status === 409 && err.code === 'CONCURRENT_REQUEST',
  );
});

test('네트워크 예외는 status 0 NETWORK ApiError', async () => {
  const fetch: FetchLike = async () => {
    throw new Error('ECONNREFUSED');
  };
  await assert.rejects(
    () => createApiClient(config, fetch).whoami(),
    (err: unknown) => err instanceof ApiError && err.status === 0 && err.code === 'NETWORK',
  );
});

test('searchCases: GET + projectBindingId·limit 쿼리', async () => {
  const { fetch, calls } = fakeFetch(200, { items: [{ id: 3, title: 't', status: 'OPEN', createdAt: '2026-01-01T00:00:00' }] });
  const res = await createApiClient(config, fetch).searchCases(5, 20);
  assert.equal(calls[0]?.url, 'https://api.test/api/v1/agent/cases?projectBindingId=5&limit=20');
  assert.equal(calls[0]?.init?.method, 'GET');
  assert.equal(res.items[0]?.id, 3);
});

test('searchCases: 인자 없으면 쿼리 없이 호출', async () => {
  const { fetch, calls } = fakeFetch(200, { items: [] });
  await createApiClient(config, fetch).searchCases();
  assert.equal(calls[0]?.url, 'https://api.test/api/v1/agent/cases');
});

test('getCase: GET /agent/cases/{id}', async () => {
  const { fetch, calls } = fakeFetch(200, { id: 7, title: 't', status: 'OPEN', project: null, projectBindingId: 5, steps: [], solutions: [] });
  const d = await createApiClient(config, fetch).getCase(7);
  assert.equal(calls[0]?.url, 'https://api.test/api/v1/agent/cases/7');
  assert.equal(d.projectBindingId, 5);
});
