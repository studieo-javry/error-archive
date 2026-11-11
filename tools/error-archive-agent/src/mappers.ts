/**
 * MCP 도구의 평탄한 입력 → API 구조화 서사(값 객체)로의 순수 변환. 부수효과 없음(테스트 용이).
 *
 * 설계 원칙(이슈 #144): 근거 없는 단정 금지. 재현/검증의 executed 는 명시하지 않으면 false 로 둔다
 * (AI 가 "제안만" 한 것을 "실행했다"로 올리지 않게). 빈 목록/빈 문자열은 전송에서 생략한다.
 */
import type {
  AttemptDetail,
  CaptureContent,
  CaptureEnvironment,
  SolutionContent,
  Verification,
} from './types.js';

function cleanList(values: string[] | undefined): string[] {
  return (values ?? []).map((v) => v.trim()).filter((v) => v.length > 0);
}

function blankToUndefined(v: string | undefined): string | undefined {
  const t = v?.trim();
  return t && t.length > 0 ? t : undefined;
}

export interface CaptureContentInput {
  symptom?: string;
  observedFacts?: string[];
  hypotheses?: string[];
  reproductionSteps?: string[];
  reproductionExecuted?: boolean;
  service?: string;
  branch?: string;
  commitSha?: string;
  dirtyWorktree?: boolean;
  diffRef?: string;
  unknowns?: string[];
}

export function buildCaptureContent(input: CaptureContentInput): CaptureContent {
  const env: CaptureEnvironment = {
    service: blankToUndefined(input.service),
    branch: blankToUndefined(input.branch),
    commitSha: blankToUndefined(input.commitSha),
    dirtyWorktree: input.dirtyWorktree,
    diffRef: blankToUndefined(input.diffRef),
  };
  const hasEnv = Object.values(env).some((v) => v !== undefined);
  const steps = cleanList(input.reproductionSteps);

  return {
    symptom: blankToUndefined(input.symptom),
    observedFacts: cleanList(input.observedFacts),
    hypotheses: cleanList(input.hypotheses),
    reproduction: steps.length > 0 ? { steps, executed: input.reproductionExecuted ?? false } : undefined,
    environment: hasEnv ? env : undefined,
    unknowns: cleanList(input.unknowns),
  };
}

export interface AttemptDetailInput {
  title: string;
  hypothesis?: string;
  action?: string;
  insight?: string;
  nextStep?: string;
  evidenceRefs?: string[];
}

export function buildAttemptDetail(input: AttemptDetailInput): AttemptDetail {
  return {
    title: input.title.trim(),
    hypothesis: blankToUndefined(input.hypothesis),
    action: blankToUndefined(input.action),
    insight: blankToUndefined(input.insight),
    nextStep: blankToUndefined(input.nextStep),
    evidenceRefs: cleanList(input.evidenceRefs),
  };
}

export interface VerificationInput {
  result: Verification['result'];
  scope?: Verification['scope'];
  command?: string;
  testId?: string;
  executed?: boolean;
  ranAt?: string;
  commitSha?: string;
  evidence?: string;
}

export interface SolutionContentInput {
  description?: string;
  clientAttemptIds: string[];
  verification?: VerificationInput;
}

export function buildSolutionContent(input: SolutionContentInput): SolutionContent {
  const v = input.verification;
  return {
    description: blankToUndefined(input.description),
    clientAttemptIds: cleanList(input.clientAttemptIds),
    verification: v
      ? {
          result: v.result,
          scope: v.scope,
          command: blankToUndefined(v.command),
          testId: blankToUndefined(v.testId),
          executed: v.executed ?? false,
          ranAt: blankToUndefined(v.ranAt),
          commitSha: blankToUndefined(v.commitSha),
          evidence: blankToUndefined(v.evidence),
        }
      : undefined,
  };
}
