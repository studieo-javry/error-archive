/**
 * 교차언어 계약 E2E — 실제 TypeScript apiClient 로 실행 중인 게이트웨이 `/api/v1/agent/**` 를 호출해,
 * Kotlin 서버가 커넥터의 JSON 을 받아들이고 응답 구조가 TS 타입과 맞는지 확인한다(MCP 계층 제외).
 *
 * 단위 테스트(test/)와 달리 실제 스택이 필요하므로, 환경변수가 없으면 전부 skip 한다:
 *   E2E_BASE_URL   = 게이트웨이 base URL (예: http://localhost:8080)
 *   E2E_AGENT_TOKEN= 테스트용 연결 토큰(eaa_...)
 * 실행: `E2E_BASE_URL=... E2E_AGENT_TOKEN=... npm run test:e2e`
 *
 * 정상 흐름(start→get→attempt→evidence→solution→complete)과, 서버가 반드시 거부해야 하는 오류
 * 흐름(401/409 revision/409 hash/422 unknown-attempt/415 unsupported/멱등 재생성)을 모두 검증한다.
 */
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { test } from 'node:test';
import { ApiError, createApiClient, type ApiClient } from '../src/apiClient.js';
import type { AgentConfig } from '../src/config.js';
import { defaultArtifactId, idempotencyKeyFor } from '../src/idempotency.js';
import type { CaptureContent } from '../src/types.js';

const BASE = process.env.E2E_BASE_URL;
const TOKEN = process.env.E2E_AGENT_TOKEN;
const SKIP: string | false =
  !BASE || !TOKEN ? 'E2E_BASE_URL/E2E_AGENT_TOKEN 미설정 — 실제 스택 대상 테스트 생략' : false;

function client(token = TOKEN!): ApiClient {
  const config: AgentConfig = { baseUrl: BASE!, token };
  return createApiClient(config);
}

const EMPTY_CONTENT: CaptureContent = { observedFacts: [], hypotheses: [], unknowns: [] };

/** 테스트용 OPEN 초안을 하나 만들고 captureUuid 를 돌려준다. */
async function freshCapture(api: ApiClient, title: string): Promise<string> {
  const captureUuid = randomUUID();
  const res = await api.createCapture(
    { captureUuid, title, content: { ...EMPTY_CONTENT, observedFacts: ['E2E 테스트 데이터'] } },
    idempotencyKeyFor('capture.create', captureUuid),
  );
  assert.equal(res.captureUuid, captureUuid);
  assert.equal(res.status, 'OPEN');
  return captureUuid;
}

/** 임시 파일을 만들고 (경로, 정리 함수) 를 돌려준다. */
async function tempFile(name: string, content: string): Promise<{ path: string; cleanup: () => Promise<void> }> {
  const dir = await mkdtemp(join(tmpdir(), 'ea-e2e-'));
  const path = join(dir, name);
  await writeFile(path, content, 'utf8');
  return { path, cleanup: () => rm(dir, { recursive: true, force: true }) };
}

test('whoami 로 연결 토큰이 사용자·연결·scope 로 해석된다', { skip: SKIP }, async () => {
  const me = await client().whoami();
  assert.ok(Number.isFinite(me.userId));
  assert.ok(me.connectionId.length > 0);
  assert.ok(Array.isArray(me.scopes));
});

test('정상 흐름: start→get→attempt→evidence→solution→complete', { skip: SKIP }, async () => {
  const api = client();
  const captureUuid = await freshCapture(api, 'Agent Capture E2E 테스트');

  // 조회: 생성 직후 상태 일치
  const detail = await api.getCapture(captureUuid);
  assert.equal(detail.capture.captureUuid, captureUuid);
  assert.equal(detail.capture.status, 'OPEN');

  // 근거 업로드(로그 파일)
  const log = await tempFile('run.log', 'line1\nline2\nE2E evidence\n');
  try {
    const artifactId = defaultArtifactId(log.path);
    const uploaded = await api.uploadArtifact(captureUuid, artifactId, log.path);
    assert.equal(uploaded.artifact.clientArtifactId, artifactId);
    assert.ok(/^[0-9a-f]{64}$/.test(uploaded.artifact.sha256));

    // 같은 경로 재업로드 → 서버가 내용 해시로 dedup(deduplicated=true)
    const again = await api.uploadArtifact(captureUuid, artifactId, log.path);
    assert.equal(again.deduplicated, true);

    // 시도: 근거 연결
    const attemptId = randomUUID();
    const attempt = await api.addAttempt(
      captureUuid,
      {
        clientAttemptId: attemptId,
        result: 'SUCCEEDED',
        attemptType: 'fix',
        detail: { title: '원인 수정', action: '널 가드 추가', evidenceRefs: [artifactId] },
      },
      idempotencyKeyFor('attempt.add', `${captureUuid}:${attemptId}`),
    );
    assert.equal(attempt.clientAttemptId, attemptId);

    // 해결 후보: 존재하는 시도 참조
    const solutionId = randomUUID();
    const solution = await api.addSolution(
      captureUuid,
      {
        clientSolutionId: solutionId,
        title: '널 가드로 해결',
        content: { description: 'E2E', clientAttemptIds: [attemptId] },
      },
      idempotencyKeyFor('solution.add', `${captureUuid}:${solutionId}`),
    );
    assert.equal(solution.clientSolutionId, solutionId);

    // 봉인
    const sealed = await api.completeCapture(
      captureUuid,
      {},
      idempotencyKeyFor('capture.complete', captureUuid),
    );
    assert.equal(sealed.status, 'READY_FOR_REVIEW');

    // 봉인 후 재조회: 시도·해결·근거가 모두 보인다
    const final = await api.getCapture(captureUuid);
    assert.equal(final.capture.status, 'READY_FOR_REVIEW');
    assert.ok(final.attempts.some((a) => a.clientAttemptId === attemptId));
    assert.ok(final.solutions.some((s) => s.clientSolutionId === solutionId));
    assert.ok(final.artifacts.some((a) => a.clientArtifactId === artifactId));
  } finally {
    await log.cleanup();
  }
});

test('401: 무효 토큰은 인증 실패', { skip: SKIP }, async () => {
  await assert.rejects(
    () => client('eaa_invalid-token-for-e2e').whoami(),
    (err: unknown) => err instanceof ApiError && err.status === 401,
  );
});

test('409 REVISION_CONFLICT: 잘못된 expectedRevision', { skip: SKIP }, async () => {
  const api = client();
  const captureUuid = await freshCapture(api, 'revision 충돌');
  await assert.rejects(
    () => api.patchCapture(captureUuid, { expectedRevision: 999, content: EMPTY_CONTENT }),
    (err: unknown) => err instanceof ApiError && err.status === 409 && err.code === 'REVISION_CONFLICT',
  );
});

test('409 ARTIFACT_HASH_MISMATCH: 같은 ID 다른 바이트', { skip: SKIP }, async () => {
  const api = client();
  const captureUuid = await freshCapture(api, 'hash 충돌');
  const a = await tempFile('a.txt', 'AAA');
  const b = await tempFile('b.txt', 'BBB');
  try {
    await api.uploadArtifact(captureUuid, 'dup-id', a.path);
    await assert.rejects(
      () => api.uploadArtifact(captureUuid, 'dup-id', b.path),
      (err: unknown) => err instanceof ApiError && err.status === 409 && err.code === 'ARTIFACT_HASH_MISMATCH',
    );
  } finally {
    await a.cleanup();
    await b.cleanup();
  }
});

test('422 UNKNOWN_ATTEMPT_REFERENCE: 없는 시도 참조', { skip: SKIP }, async () => {
  const api = client();
  const captureUuid = await freshCapture(api, 'unknown attempt');
  await assert.rejects(
    () =>
      api.addSolution(
        captureUuid,
        { clientSolutionId: randomUUID(), title: 'x', content: { clientAttemptIds: ['does-not-exist'] } },
        idempotencyKeyFor('solution.add', `${captureUuid}:x`),
      ),
    (err: unknown) => err instanceof ApiError && err.status === 422 && err.code === 'UNKNOWN_ATTEMPT_REFERENCE',
  );
});

test('415 UNSUPPORTED_ARTIFACT: 마크업(HTML) 거부', { skip: SKIP }, async () => {
  const api = client();
  const captureUuid = await freshCapture(api, 'unsupported artifact');
  const html = await tempFile('evil.txt', '<!doctype html><html><body>no</body></html>');
  try {
    await assert.rejects(
      () => api.uploadArtifact(captureUuid, 'markup', html.path),
      (err: unknown) => err instanceof ApiError && err.status === 415 && err.code === 'UNSUPPORTED_ARTIFACT',
    );
  } finally {
    await html.cleanup();
  }
});

test('멱등: 같은 captureUuid+키 재생성은 중복 없이 같은 초안', { skip: SKIP }, async () => {
  const api = client();
  const captureUuid = randomUUID();
  const key = idempotencyKeyFor('capture.create', captureUuid);
  const req = { captureUuid, title: '멱등', content: EMPTY_CONTENT };
  const first = await api.createCapture(req, key);
  const second = await api.createCapture(req, key); // 같은 키·같은 페이로드 → 서버가 replay
  assert.equal(first.captureUuid, captureUuid);
  assert.equal(second.captureUuid, captureUuid);
  assert.equal(second.revision, first.revision);
});
