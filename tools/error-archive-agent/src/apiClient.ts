/**
 * error-archive agent-capture API 클라이언트. 게이트웨이 `/api/v1/agent/**` 를 Bearer 연결 토큰으로 호출.
 *
 *  - 모든 요청에 `Authorization: Bearer <token>`.
 *  - 생성/봉인 계열은 `Idempotency-Key` 를 요구 — 호출 측이 넘기지 않으면 새 키를 생성한다(재시도 시
 *    같은 키를 넘겨야 중복 생성이 방지된다).
 *  - 비 2xx 응답은 problem detail 의 `code` 를 실어 ApiError 로 던진다(커넥터가 분기/보고).
 *  - fetch 는 주입 가능(테스트). 기본 globalThis.fetch(Node 18+).
 */
import { readFile } from 'node:fs/promises';
import { basename } from 'node:path';
import type { AgentConfig } from './config.js';
import { maskToken } from './config.js';
import { newIdempotencyKey } from './idempotency.js';
import type {
  AddAttemptRequest,
  AddSolutionRequest,
  AgentCaseDetail,
  AgentCaseListResponse,
  AgentIdentity,
  AttemptResponse,
  CaptureDetailResponse,
  CaptureResponse,
  CompleteCaptureRequest,
  CreateCaptureRequest,
  PatchCaptureRequest,
  RegisterSessionRequest,
  SessionResponse,
  SolutionResponse,
  UpdateAttemptRequest,
  UploadArtifactResult,
} from './types.js';

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

/**
 * 커넥터가 서버에 신고하는 설치 메타(표시용). 연결 목록을 사람이 알아보게 하려는 값으로,
 * 모든 요청에 X-Agent-* 헤더로 실린다. 서버는 인가에 쓰지 않는다(위조 가능).
 */
export interface AgentClientInfo {
  kind: string;
  version?: string;
  installId: string;
  installLabel?: string;
}

/** AgentClientInfo → X-Agent-* 헤더(빈 값은 생략). */
function clientHeaders(client: AgentClientInfo | undefined): Record<string, string> {
  if (!client) return {}
  const h: Record<string, string> = {}
  if (client.kind) h['X-Agent-Client-Kind'] = client.kind
  if (client.version) h['X-Agent-Client-Version'] = client.version
  if (client.installId) h['X-Agent-Install-Id'] = client.installId
  if (client.installLabel) h['X-Agent-Install-Label'] = client.installLabel
  return h
}

/** 서버가 돌려준 오류. status + (있으면) problem detail code 를 보존한다. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    message: string,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

interface RequestOptions {
  method: string;
  path: string;
  body?: unknown;
  idempotencyKey?: string;
  /** JSON 이 아닌 본문(multipart). 설정 시 body 는 무시. */
  rawBody?: RequestInit['body'];
}

export interface ApiClient {
  whoami(): Promise<AgentIdentity>;
  registerSession(req: RegisterSessionRequest, idempotencyKey?: string): Promise<SessionResponse>;
  createCapture(req: CreateCaptureRequest, idempotencyKey?: string): Promise<CaptureResponse>;
  getCapture(captureUuid: string): Promise<CaptureDetailResponse>;
  patchCapture(captureUuid: string, req: PatchCaptureRequest): Promise<CaptureResponse>;
  addAttempt(captureUuid: string, req: AddAttemptRequest, idempotencyKey?: string): Promise<AttemptResponse>;
  updateAttempt(captureUuid: string, clientAttemptId: string, req: UpdateAttemptRequest): Promise<AttemptResponse>;
  addSolution(captureUuid: string, req: AddSolutionRequest, idempotencyKey?: string): Promise<SolutionResponse>;
  uploadArtifact(captureUuid: string, clientArtifactId: string, filePath: string): Promise<UploadArtifactResult>;
  completeCapture(captureUuid: string, req: CompleteCaptureRequest, idempotencyKey?: string): Promise<CaptureResponse>;
  /** 허용 범위(본인 소유 + 바인딩 귀속)의 과거 사례 검색. */
  searchCases(projectBindingId?: number, limit?: number): Promise<AgentCaseListResponse>;
  /** 과거 사례 상세(본인 소유 + 바인딩 귀속만, 아니면 404). */
  getCase(caseId: number): Promise<AgentCaseDetail>;
}

/** 파일 확장자 → MIME(서버가 매직 바이트로 재검사하므로 추정이면 충분). */
const MIME_BY_EXT: Record<string, string> = {
  png: 'image/png',
  jpg: 'image/jpeg',
  jpeg: 'image/jpeg',
  webp: 'image/webp',
  json: 'application/json',
  txt: 'text/plain',
  log: 'text/plain',
  diff: 'text/plain',
  patch: 'text/plain',
};

function guessContentType(fileName: string): string {
  const ext = fileName.toLowerCase().split('.').pop() ?? '';
  return MIME_BY_EXT[ext] ?? 'application/octet-stream';
}

export function createApiClient(config: AgentConfig, fetchImpl?: FetchLike, client?: AgentClientInfo): ApiClient {
  const doFetch: FetchLike = fetchImpl ?? ((input, init) => fetch(input, init));
  const installHeaders = clientHeaders(client);

  async function request<T>(opts: RequestOptions): Promise<T> {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${config.token}`,
      Accept: 'application/json',
      ...installHeaders,
    };
    let body: RequestInit['body'];
    if (opts.rawBody !== undefined) {
      body = opts.rawBody; // multipart — Content-Type 은 런타임이 boundary 와 함께 설정
    } else if (opts.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(opts.body);
    }
    if (opts.idempotencyKey) headers['Idempotency-Key'] = opts.idempotencyKey;

    // 같은 멱등키가 동시에 in-flight 면 서버가 409 CONCURRENT_REQUEST(재시도 가능)를 준다. 같은 키로
    // 다시 보내는 건 구성상 안전하므로(중복 생성 불가) 한 번만 재시도한다. 그 외 오류는 그대로 올린다.
    for (let attempt = 0; ; attempt++) {
      let res: Response;
      try {
        res = await doFetch(`${config.baseUrl}${opts.path}`, { method: opts.method, headers, body });
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        throw new ApiError(0, 'NETWORK', `요청 실패(${opts.method} ${opts.path}): ${reason}`);
      }

      const text = await res.text();
      if (!res.ok) {
        let code: string | undefined;
        let detail = text;
        try {
          const problem = JSON.parse(text) as { code?: string; detail?: string; title?: string };
          code = problem.code;
          detail = problem.detail ?? problem.title ?? text;
        } catch {
          /* 비 JSON 오류 본문 — 원문 유지 */
        }
        if (res.status === 409 && code === 'CONCURRENT_REQUEST' && attempt < 1) continue;
        if (res.status === 401) {
          throw new ApiError(401, code, `인증 실패(토큰 ${maskToken(config.token)} 무효/폐기/만료).`);
        }
        throw new ApiError(res.status, code, `${opts.method} ${opts.path} → ${res.status}${code ? ` ${code}` : ''}: ${detail}`);
      }
      return (text ? JSON.parse(text) : undefined) as T;
    }
  }

  return {
    whoami: () => request<AgentIdentity>({ method: 'GET', path: '/api/v1/agent/me' }),

    registerSession: (req, key) =>
      request<SessionResponse>({ method: 'POST', path: '/api/v1/agent/sessions', body: req, idempotencyKey: key ?? newIdempotencyKey() }),

    createCapture: (req, key) =>
      request<CaptureResponse>({ method: 'POST', path: '/api/v1/agent/captures', body: req, idempotencyKey: key ?? newIdempotencyKey() }),

    getCapture: (uuid) =>
      request<CaptureDetailResponse>({ method: 'GET', path: `/api/v1/agent/captures/${uuid}` }),

    patchCapture: (uuid, req) =>
      request<CaptureResponse>({ method: 'PATCH', path: `/api/v1/agent/captures/${uuid}`, body: req }),

    addAttempt: (uuid, req, key) =>
      request<AttemptResponse>({ method: 'POST', path: `/api/v1/agent/captures/${uuid}/attempts`, body: req, idempotencyKey: key ?? newIdempotencyKey() }),

    updateAttempt: (uuid, clientAttemptId, req) =>
      request<AttemptResponse>({ method: 'PATCH', path: `/api/v1/agent/captures/${uuid}/attempts/${encodeURIComponent(clientAttemptId)}`, body: req }),

    addSolution: (uuid, req, key) =>
      request<SolutionResponse>({ method: 'POST', path: `/api/v1/agent/captures/${uuid}/solutions`, body: req, idempotencyKey: key ?? newIdempotencyKey() }),

    completeCapture: (uuid, req, key) =>
      request<CaptureResponse>({ method: 'POST', path: `/api/v1/agent/captures/${uuid}/complete`, body: req, idempotencyKey: key ?? newIdempotencyKey() }),

    searchCases: (projectBindingId, limit) => {
      const qs = new URLSearchParams();
      if (projectBindingId != null) qs.set('projectBindingId', String(projectBindingId));
      if (limit != null) qs.set('limit', String(limit));
      const suffix = qs.toString() ? `?${qs}` : '';
      return request<AgentCaseListResponse>({ method: 'GET', path: `/api/v1/agent/cases${suffix}` });
    },

    getCase: (caseId) =>
      request<AgentCaseDetail>({ method: 'GET', path: `/api/v1/agent/cases/${caseId}` }),

    async uploadArtifact(uuid, clientArtifactId, filePath) {
      const bytes = await readFile(filePath);
      const fileName = basename(filePath);
      const form = new FormData();
      // 실제 바이트를 전송한다(로컬 경로 문자열만 보내는 것 금지 — 서버가 형식·크기 재검사).
      form.append('file', new Blob([bytes], { type: guessContentType(fileName) }), fileName);
      form.append('clientArtifactId', clientArtifactId);
      // 서버는 신규 201 / 멱등 재업로드 200 로 구분한다.
      const headers: Record<string, string> = {
        Authorization: `Bearer ${config.token}`,
        Accept: 'application/json',
        ...installHeaders,
      };
      let res: Response;
      try {
        res = await doFetch(`${config.baseUrl}/api/v1/agent/captures/${uuid}/artifacts`, { method: 'POST', headers, body: form });
      } catch (err) {
        const reason = err instanceof Error ? err.message : String(err);
        throw new ApiError(0, 'NETWORK', `첨부 업로드 실패: ${reason}`);
      }
      const text = await res.text();
      if (!res.ok) {
        let code: string | undefined;
        let detail = text;
        try {
          const problem = JSON.parse(text) as { code?: string; detail?: string };
          code = problem.code;
          detail = problem.detail ?? text;
        } catch {
          /* 비 JSON */
        }
        throw new ApiError(res.status, code, `첨부 업로드 → ${res.status}${code ? ` ${code}` : ''}: ${detail}`);
      }
      return { artifact: JSON.parse(text), deduplicated: res.status === 200 };
    },
  };
}
