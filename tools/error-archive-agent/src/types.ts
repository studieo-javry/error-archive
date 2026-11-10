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
}

export interface CreateCaptureRequest {
  captureUuid: string;
  title?: string;
  project?: string;
  content: CaptureContent;
  sessionUuid?: string;
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

// ── Hook 자동수집 이벤트(P1) ─────────────────────────────────────────────────

/**
 * 정규화한 CaptureEvent 1건(서버 EventItemRequest 와 1:1). `clientEventId` 는 최초 로컬 저장 시
 * 1회 생성해 재전송에서도 유지한다 — (connectionId, clientEventId) 로 서버가 멱등 처리한다.
 * `redactedPayload` 는 마스킹된 JSON(≤64 KiB). 더 큰 근거는 첨부로 올린다.
 */
export interface EventItemRequest {
  clientEventId: string;
  eventType: string;
  localSequence: number;
  /** 클라이언트 관측 시각(ISO-8601). 서버 수신 시각과 구분 저장된다. */
  observedAt: string;
  redactedPayload: unknown;
  projectBindingId?: number | null;
  clientKind?: string | null;
  clientVersion?: string | null;
  sessionId?: string | null;
  streamId?: string | null;
  toolCallId?: string | null;
  truncated?: boolean;
  evidenceRefs?: string[];
}

export interface EventBatchRequest {
  events: EventItemRequest[];
}

/** 이벤트별 적재 결과: ACCEPTED(저장) | DUPLICATE(재전송) | REJECTED(충돌·과대 등). */
export type EventResultType = 'ACCEPTED' | 'DUPLICATE' | 'REJECTED';

export interface EventResultResponse {
  clientEventId: string;
  result: EventResultType;
  reason?: string | null;
}

export interface EventBatchResponse {
  results: EventResultResponse[];
}

/** 미분류 이벤트를 OPEN 초안에 연결한 결과(linked=연결됨/멱등 포함, skipped=없음/타 초안 소속). */
export interface LinkEventsResponse {
  linked: string[];
  skipped: string[];
}

// ── case:read (허용 범위 과거 사례 조회) ──────────────────────────────────────

/** 검색 결과 요약(본인 소유 + ProjectBinding 귀속 케이스만). */
export interface AgentCaseSummary {
  id: number;
  title: string;
  status: string;
  createdAt: string;
}

export interface AgentCaseListResponse {
  items: AgentCaseSummary[];
}

export interface AgentCaseStep {
  title: string;
  status: string;
}

export interface AgentCaseSolution {
  title: string;
}

/** 상세(Step·Solution 포함). */
export interface AgentCaseDetail {
  id: number;
  title: string;
  status: string;
  project: string | null;
  projectBindingId: number | null;
  steps: AgentCaseStep[];
  solutions: AgentCaseSolution[];
}
