/**
 * error-archive agent-capture API 의 요청/응답 계약(core-api 의 DTO 와 1:1).
 *
 * 게이트웨이의 `/api/v1/agent/**` 경로를 opaque 연결 토큰(`eaa_...`)으로 호출한다. 서버가 토큰을
 * 사용자·연결·scope 로 해석하므로, 커넥터는 토큰만 실어 보낸다(사용자 JWT 아님).
 */

// ── 열거형(서버 enum 과 동일 문자열) ───────────────────────────────────────────

export type AttemptResult = 'PENDING' | 'SUCCEEDED' | 'FAILED' | 'INCONCLUSIVE';
export type VerificationResult = 'NOT_RUN' | 'PASSED' | 'FAILED' | 'INCONCLUSIVE';
export type VerificationScope = 'LOCAL' | 'CI' | 'MANUAL';
export type CaptureStatus = 'OPEN' | 'READY_FOR_REVIEW' | 'IMPORTED' | 'DISCARDED';

// ── 구조화 서사(값 객체) ───────────────────────────────────────────────────────

/** 재현 절차 + 실제 실행 여부(근거 없이 성공 확정 금지). */
export interface Reproduction {
  steps: string[];
  executed: boolean;
}

/** 관측 가능한 실행 환경. */
export interface CaptureEnvironment {
  service?: string;
  branch?: string;
  commitSha?: string;
  dirtyWorktree?: boolean;
  /** 변경 diff 의 식별자(내용 자체가 아닌 참조). */
  diffRef?: string;
}

/**
 * Capture 초안의 서사 필드(사실·가설·판단 분리). 근거가 없으면 원인·검증·판단을 임의로 채우지 않는다.
 */
export interface CaptureContent {
  symptom?: string;
  observedFacts: string[];
  hypotheses: string[];
  reproduction?: Reproduction;
  environment?: CaptureEnvironment;
  unknowns: string[];
}

/** Attempt 의 서사 필드(결과·순서·clientId 는 상위). */
export interface AttemptDetail {
  title: string;
  hypothesis?: string;
  action?: string;
  insight?: string;
  nextStep?: string;
  /** 근거(첨부) 참조 — clientArtifactId 목록. 봉인 전 모두 업로드돼야 한다. */
  evidenceRefs: string[];
}

/** 검증 근거. PASSED 는 '지정 범위에서 관측한 결과'일 뿐 운영 전체 해결을 보증하지 않는다. */
export interface Verification {
  result: VerificationResult;
  scope?: VerificationScope;
  command?: string;
  testId?: string;
  executed: boolean;
  ranAt?: string;
  commitSha?: string;
  evidence?: string;
}

/** Solution 후보의 서사 필드. 최소 1개의 존재하는 Attempt(clientAttemptId)를 참조해야 한다. */
export interface SolutionContent {
  description?: string;
  clientAttemptIds: string[];
  verification?: Verification;
}

// ── 요청 본문 ──────────────────────────────────────────────────────────────────

export interface RegisterSessionRequest {
  sessionUuid: string;
  clientKind: string;
  clientVersion?: string;
  clientSessionId: string;
  /** 세션의 프로젝트 귀속(본인 소유 ProjectBinding). 생성될 Capture 가 상속. projectKey 보다 우선. */
  projectBindingId?: number;
  /** 프로젝트 식별자 — 바인딩 없으면 본인 소유로 생성(findOrCreate). */
  projectKey?: string;
}

export interface CreateCaptureRequest {
  captureUuid: string;
  title?: string;
  project?: string;
  content: CaptureContent;
  sessionUuid?: string;
  /** 명시 프로젝트 귀속(선택). 미지정 시 세션의 projectBindingId 상속. */
  projectBindingId?: number;
}

export interface PatchCaptureRequest {
  expectedRevision?: number;
  title?: string;
  project?: string;
  content: CaptureContent;
}

export interface AddAttemptRequest {
  clientAttemptId: string;
  result: AttemptResult;
  attemptType?: string;
  detail: AttemptDetail;
}

export interface UpdateAttemptRequest {
  result: AttemptResult;
  attemptType?: string;
  detail: AttemptDetail;
}

export interface AddSolutionRequest {
  clientSolutionId: string;
  title: string;
  content: SolutionContent;
}

export interface UpdateSolutionRequest {
  title: string;
  content: SolutionContent;
}

export interface CompleteCaptureRequest {
  expectedRevision?: number;
}

// ── 응답 본문 ──────────────────────────────────────────────────────────────────

export interface AgentIdentity {
  userId: number;
  connectionId: string;
  scopes: string[];
}

export interface SessionResponse {
  sessionUuid: string;
  connectionId: string;
  clientKind: string;
  clientVersion: string | null;
  clientSessionId: string;
  startedAt: string;
}

export interface CaptureResponse {
  captureUuid: string;
  status: CaptureStatus;
  revision: number;
  title: string | null;
  project: string | null;
  content: CaptureContent;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface AttemptResponse {
  clientAttemptId: string;
  orderIndex: number;
  result: AttemptResult;
  attemptType: string | null;
  detail: AttemptDetail;
  createdAt: string;
  updatedAt: string;
}

export interface SolutionResponse {
  clientSolutionId: string;
  title: string;
  content: SolutionContent;
  createdAt: string;
}

export interface ArtifactResponse {
  clientArtifactId: string;
  sha256: string;
  contentType: string;
  sizeBytes: number;
  fileName: string | null;
  status: string;
  createdAt: string;
}

export interface CaptureDetailResponse {
  capture: CaptureResponse;
  attempts: AttemptResponse[];
  solutions: SolutionResponse[];
  artifacts: ArtifactResponse[];
}

/** 업로드 결과 — 서버가 201(신규)/200(멱등 재업로드)로 구분한다. */
export interface UploadArtifactResult {
  artifact: ArtifactResponse;
  deduplicated: boolean;
}

// ── case:read (과거 해결경험 조회 — ProjectBinding 독립) ──────────────────────

/** 검색 결과 요약(본인 소유). */
export interface AgentCaseSummary {
  id: number;
  title: string;
  status: string;
  exceptionClass: string | null;
  createdAt: string;
}

export interface AgentCaseListResponse {
  items: AgentCaseSummary[];
}

/** 상세의 Step — 본문 포함(해결 과정 재활용). */
export interface AgentCaseStep {
  orderIndex: number;
  title: string;
  status: string;
  attemptType: string | null;
  body: string | null;
  insight: string | null;
}

export interface AgentCaseVerification {
  result: string | null;
  executed: boolean;
  command: string | null;
  testId: string | null;
  ranAt: string | null;
  commitSha: string | null;
  evidence: string | null;
}

export interface AgentCaseSolution {
  title: string;
  description: string | null;
  verification: AgentCaseVerification | null;
}

/** 상세 — Step 본문·Solution 설명·검증 포함. */
export interface AgentCaseDetail {
  id: number;
  title: string;
  status: string;
  project: string | null;
  projectBindingId: number | null;
  description: string | null;
  exceptionClass: string | null;
  exceptionMessage: string | null;
  steps: AgentCaseStep[];
  solutions: AgentCaseSolution[];
}

// ── Hook 이벤트 수집 (events/batch) ───────────────────────────────────────────

export type JsonValue = string | number | boolean | null | JsonValue[] | { [k: string]: JsonValue };

/** 정규화한 Hook 이벤트 1건(서버 EventItemRequest 와 정합). redactedPayload 는 마스킹된 JSON(≤64KiB). */
export interface EventItemRequest {
  clientEventId: string;
  eventType: string;
  localSequence: number;
  /** ISO-8601 관측 시각. */
  observedAt: string;
  redactedPayload: JsonValue;
  truncated: boolean;
  evidenceRefs: string[];
  projectBindingId?: number | null;
  clientKind?: string | null;
  clientVersion?: string | null;
  sessionId?: string | null;
  streamId?: string | null;
  toolCallId?: string | null;
}

export interface EventBatchRequest {
  events: EventItemRequest[];
}

export interface EventResultResponse {
  clientEventId: string;
  /** ACCEPTED | DUPLICATE | REJECTED */
  result: string;
  reason?: string | null;
}

export interface EventBatchResponse {
  results: EventResultResponse[];
}
