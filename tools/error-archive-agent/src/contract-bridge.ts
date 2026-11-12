/**
 * 손 타입 ↔ OpenAPI 계약 컴파일타임 브리지 (이슈 #144, 항목 b).
 *
 * apiClient 가 쓰는 손 타입(`types.ts`)이 계약에서 생성된 타입(`generated/agent-api.ts`)과
 * 어긋나지 않는지 **typecheck 단계에서** 강제한다. 생성 타입은 Kotlin nullable 을 `?: T | null`
 * (선택)로, enum 을 `string` 으로 느슨하게 표현하는 반면, 손 타입은 `: T | null`(필수·nullable)·
 * 리터럴 유니온으로 **더 엄격**하다. 따라서 올바른 불변식은 **손 ⊆ 계약**(손 타입이 계약의 더 엄격한
 * 부분집합): 요청은 "보내는 값이 계약상 유효", 응답은 "손 타입이 계약 범위를 벗어나지 않음".
 *
 * 이 파일은 **타입 전용**이라 런타임 산출물이 없다. 필드 타입 변경·요청 필수필드 추가 등 계약 드리프트가
 * 손 타입에 반영되지 않으면 여기서 컴파일이 깨진다. (Kotlin↔스냅샷은 AgentOpenApiContractTest,
 * 스냅샷↔생성타입은 openapi:check 가 담당 — 이 브리지가 그 사슬을 손 타입까지 잇는다.)
 */
import type { components } from './generated/agent-api.js';
import type {
  RegisterSessionRequest,
  CreateCaptureRequest,
  PatchCaptureRequest,
  AddAttemptRequest,
  UpdateAttemptRequest,
  AddSolutionRequest,
  CompleteCaptureRequest,
  AgentIdentity,
  SessionResponse,
  CaptureResponse,
  AttemptResponse,
  SolutionResponse,
  ArtifactResponse,
  CaptureDetailResponse,
  CaptureContent,
  AttemptDetail,
  Verification,
  SolutionContent,
  Reproduction,
  CaptureEnvironment,
} from './types.js';

type Schemas = components['schemas'];

/** A 가 B 에 할당 가능하면 true, 아니면 불일치 내용을 담은 객체(= Expect 에서 컴파일 실패). */
type AssignableTo<A, B> = [A] extends [B] ? true : { __bridgeMismatch: true; hand: A; contract: B };

/** 인자가 true 가 아니면(= 불일치) 제약 위반으로 컴파일 에러. */
type Expect<T extends true> = T;

// --- 요청: 손 ⊆ 계약 (커넥터가 보내는 값이 계약상 유효한 입력) ---
type _RegisterSessionRequest = Expect<AssignableTo<RegisterSessionRequest, Schemas['RegisterSessionRequest']>>;
type _CreateCaptureRequest = Expect<AssignableTo<CreateCaptureRequest, Schemas['CreateCaptureRequest']>>;
type _PatchCaptureRequest = Expect<AssignableTo<PatchCaptureRequest, Schemas['PatchCaptureRequest']>>;
type _AddAttemptRequest = Expect<AssignableTo<AddAttemptRequest, Schemas['AddAttemptRequest']>>;
type _UpdateAttemptRequest = Expect<AssignableTo<UpdateAttemptRequest, Schemas['UpdateAttemptRequest']>>;
type _AddSolutionRequest = Expect<AssignableTo<AddSolutionRequest, Schemas['AddSolutionRequest']>>;
type _CompleteCaptureRequest = Expect<AssignableTo<CompleteCaptureRequest, Schemas['CompleteCaptureRequest']>>;

// --- 응답: 손 ⊆ 계약 (손 타입이 계약이 허용하는 범위를 벗어나지 않음) ---
type _AgentIdentity = Expect<AssignableTo<AgentIdentity, Schemas['AgentIdentityResponse']>>;
type _SessionResponse = Expect<AssignableTo<SessionResponse, Schemas['SessionResponse']>>;
type _CaptureResponse = Expect<AssignableTo<CaptureResponse, Schemas['CaptureResponse']>>;
type _AttemptResponse = Expect<AssignableTo<AttemptResponse, Schemas['AttemptResponse']>>;
type _SolutionResponse = Expect<AssignableTo<SolutionResponse, Schemas['SolutionResponse']>>;
type _ArtifactResponse = Expect<AssignableTo<ArtifactResponse, Schemas['ArtifactResponse']>>;
type _CaptureDetailResponse = Expect<AssignableTo<CaptureDetailResponse, Schemas['CaptureDetailResponse']>>;

// --- 공유 VO: 요청·응답에 임베드되므로 손 ⊆ 계약 ---
type _CaptureContent = Expect<AssignableTo<CaptureContent, Schemas['CaptureContent']>>;
type _AttemptDetail = Expect<AssignableTo<AttemptDetail, Schemas['AttemptDetail']>>;
type _Verification = Expect<AssignableTo<Verification, Schemas['Verification']>>;
type _SolutionContent = Expect<AssignableTo<SolutionContent, Schemas['SolutionContent']>>;
type _Reproduction = Expect<AssignableTo<Reproduction, Schemas['Reproduction']>>;
type _CaptureEnvironment = Expect<AssignableTo<CaptureEnvironment, Schemas['CaptureEnvironment']>>;
