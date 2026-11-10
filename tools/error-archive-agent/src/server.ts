/**
 * MCP 서버 — Claude Code 등 로컬 AI 도구가 개발 중 오류·해결 과정을 error-archive 초안으로 기록하도록
 * 도구를 노출한다. 모든 도구는 ApiClient 를 통해 게이트웨이 `/api/v1/agent/**` 를 호출한다.
 *
 * 도구는 서버 계약과 1:1 로 얇게 매핑하고, 서사 조립은 mappers(순수) 에 위임한다. 봉인(complete)은
 * 자동 호출하지 않는다 — 사용자/정책이 명시적으로 부르게 둔다(미검토 기록이 함부로 올라오지 않게).
 */
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import type { CallToolResult } from '@modelcontextprotocol/sdk/types.js';
import { z } from 'zod';
import type { ApiClient } from './apiClient.js';
import { ApiError } from './apiClient.js';
import { defaultArtifactId, idempotencyKeyFor, newUuid } from './idempotency.js';
import { buildAttemptDetail, buildCaptureContent, buildSolutionContent } from './mappers.js';

function ok(data: unknown): CallToolResult {
  return { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] };
}

function fail(message: string): CallToolResult {
  return { content: [{ type: 'text', text: message }], isError: true };
}

/** 도구 핸들러 공통 에러 변환 — ApiError 는 코드/상태를 실어 보고, 그 외는 메시지만. */
async function guard(run: () => Promise<CallToolResult>): Promise<CallToolResult> {
  try {
    return await run();
  } catch (err) {
    if (err instanceof ApiError) {
      return fail(`API 오류(status=${err.status}${err.code ? `, code=${err.code}` : ''}): ${err.message}`);
    }
    return fail(err instanceof Error ? err.message : String(err));
  }
}

const RESULT = z.enum(['PENDING', 'SUCCEEDED', 'FAILED', 'INCONCLUSIVE']);
const VERIFICATION_RESULT = z.enum(['NOT_RUN', 'PASSED', 'FAILED', 'INCONCLUSIVE']);
const VERIFICATION_SCOPE = z.enum(['LOCAL', 'CI', 'MANUAL']);

/** 서사 입력(capture_start / capture_update 공용). */
const CONTENT_SHAPE = {
  symptom: z.string().optional().describe('한 줄 증상 요약'),
  observedFacts: z.array(z.string()).optional().describe('실행 로그·테스트 결과 등 실제 관측된 사실'),
  hypotheses: z.array(z.string()).optional().describe('원인 후보(가설). 확정이 아님'),
  reproductionSteps: z.array(z.string()).optional().describe('재현 절차'),
  reproductionExecuted: z.boolean().optional().describe('재현을 실제로 실행했는가(미확인이면 생략/false)'),
  service: z.string().optional(),
  branch: z.string().optional(),
  commitSha: z.string().optional(),
  dirtyWorktree: z.boolean().optional(),
  diffRef: z.string().optional().describe('변경 diff 의 식별자(내용이 아닌 참조)'),
  unknowns: z.array(z.string()).optional().describe('아직 확인하지 못한 내용'),
};

export function createServer(client: ApiClient): McpServer {
  const server = new McpServer({ name: 'error-archive-agent', version: '0.1.0' });

  server.registerTool(
    'diagnose',
    {
      title: '연결 진단',
      description: '설정된 토큰이 어떤 사용자·연결·scope 로 해석되는지 확인한다(doctor). 오류 설정을 빨리 잡는 용도.',
      inputSchema: {},
    },
    () => guard(async () => ok(await client.whoami())),
  );

  server.registerTool(
    'capture_start',
    {
      title: '초안 수집 시작',
      description:
        '새 OPEN 초안을 만든다. captureUuid 는 서버가 아닌 이 도구가 부여하므로, 반환된 captureUuid 를 이후 attempt/solution/evidence/complete 에 전달하라. 근거 없는 원인 단정은 넣지 말 것.',
      inputSchema: {
        title: z.string().optional(),
        project: z.string().optional(),
        sessionUuid: z.string().uuid().optional().describe('이 초안을 이을 세션(선택)'),
        ...CONTENT_SHAPE,
      },
    },
    (args) =>
      guard(async () => {
        const captureUuid = newUuid();
        const created = await client.createCapture(
          {
            captureUuid,
            title: args.title,
            project: args.project,
            content: buildCaptureContent(args),
            sessionUuid: args.sessionUuid,
          },
          idempotencyKeyFor('capture.create', captureUuid),
        );
        return ok({ captureUuid: created.captureUuid, status: created.status, revision: created.revision });
      }),
  );

  server.registerTool(
    'capture_add_attempt',
    {
      title: '시도 추가',
      description:
        '하나의 의미 있는 시도(조사·수정·검증)를 기록한다. clientAttemptId 를 생략하면 자동 생성해 반환한다. result 는 실제 관측 결과로 — 미확인이면 PENDING/INCONCLUSIVE.',
      inputSchema: {
        captureUuid: z.string().uuid(),
        clientAttemptId: z.string().optional(),
        result: RESULT,
        attemptType: z.string().optional().describe('예: investigate, fix, verify'),
        title: z.string(),
        hypothesis: z.string().optional(),
        action: z.string().optional().describe('실제 수행한 내용'),
        insight: z.string().optional().describe('이 시도에서 얻은 판단'),
        nextStep: z.string().optional(),
        evidenceRefs: z.array(z.string()).optional().describe('연결할 근거(clientArtifactId). 봉인 전 업로드돼야 함'),
      },
    },
    (args) =>
      guard(async () => {
        const clientAttemptId = args.clientAttemptId?.trim() || newUuid();
        const saved = await client.addAttempt(
          args.captureUuid,
          {
            clientAttemptId,
            result: args.result,
            attemptType: args.attemptType,
            detail: buildAttemptDetail(args),
          },
          idempotencyKeyFor('attempt.add', `${args.captureUuid}:${clientAttemptId}`),
        );
        return ok({ clientAttemptId: saved.clientAttemptId, orderIndex: saved.orderIndex, result: saved.result });
      }),
  );

  server.registerTool(
    'capture_add_solution',
    {
      title: '해결 후보 추가',
      description:
        '해결 후보를 기록한다. clientAttemptIds 는 같은 초안의 존재하는 시도를 가리켜야 한다. 검증 결과(PASSED 등)는 실제 실행 근거가 있을 때만.',
      inputSchema: {
        captureUuid: z.string().uuid(),
        clientSolutionId: z.string().optional(),
        title: z.string(),
        description: z.string().optional(),
        clientAttemptIds: z.array(z.string()).min(1).describe('기여한 시도(clientAttemptId) — 최소 1개'),
        verificationResult: VERIFICATION_RESULT.optional(),
        verificationScope: VERIFICATION_SCOPE.optional(),
        verificationCommand: z.string().optional(),
        verificationExecuted: z.boolean().optional(),
        verificationEvidence: z.string().optional(),
      },
    },
    (args) =>
      guard(async () => {
        const clientSolutionId = args.clientSolutionId?.trim() || newUuid();
        const verification = args.verificationResult
          ? {
              result: args.verificationResult,
              scope: args.verificationScope,
              command: args.verificationCommand,
              executed: args.verificationExecuted,
              evidence: args.verificationEvidence,
            }
          : undefined;
        const saved = await client.addSolution(
          args.captureUuid,
          {
            clientSolutionId,
            title: args.title,
            content: buildSolutionContent({
              description: args.description,
              clientAttemptIds: args.clientAttemptIds,
              verification,
            }),
          },
          idempotencyKeyFor('solution.add', `${args.captureUuid}:${clientSolutionId}`),
        );
        return ok({ clientSolutionId: saved.clientSolutionId, title: saved.title });
      }),
  );

  server.registerTool(
    'capture_attach_evidence',
    {
      title: '근거 첨부',
      description:
        '로컬 파일(스크린샷·로그·diff·JSON)을 초안 근거로 업로드한다. 반환된 clientArtifactId 를 시도의 evidenceRefs 에 연결하라. 허용: PNG/JPEG/WebP·UTF-8 텍스트/로그/diff·JSON(파일당 10MiB).',
      inputSchema: {
        captureUuid: z.string().uuid(),
        filePath: z.string().describe('업로드할 로컬 파일의 절대/상대 경로'),
        clientArtifactId: z.string().optional().describe('근거 식별자(생략 시 파일경로 기반 자동 — 동명 다른 파일도 구분)'),
      },
    },
    (args) =>
      guard(async () => {
        const clientArtifactId = args.clientArtifactId?.trim() || defaultArtifactId(args.filePath);
        const outcome = await client.uploadArtifact(args.captureUuid, clientArtifactId, args.filePath);
        return ok({
          clientArtifactId: outcome.artifact.clientArtifactId,
          sha256: outcome.artifact.sha256,
          contentType: outcome.artifact.contentType,
          sizeBytes: outcome.artifact.sizeBytes,
          deduplicated: outcome.deduplicated,
        });
      }),
  );

  server.registerTool(
    'capture_update',
    {
      title: '초안 기본 정보 수정',
      description:
        'OPEN 초안의 title/project/서사(content 전체 교체)를 갱신한다. expectedRevision 을 넘기면 낙관 충돌 시 409.',
      inputSchema: {
        captureUuid: z.string().uuid(),
        expectedRevision: z.number().int().optional(),
        title: z.string().optional(),
        project: z.string().optional(),
        ...CONTENT_SHAPE,
      },
    },
    (args) =>
      guard(async () => {
        const updated = await client.patchCapture(args.captureUuid, {
          expectedRevision: args.expectedRevision,
          title: args.title,
          project: args.project,
          content: buildCaptureContent(args),
        });
        return ok({ captureUuid: updated.captureUuid, status: updated.status, revision: updated.revision });
      }),
  );

  server.registerTool(
    'capture_get',
    {
      title: '초안 조회',
      description: '초안의 현재 상태(시도·해결·근거 포함)를 조회한다.',
      inputSchema: { captureUuid: z.string().uuid() },
    },
    (args) => guard(async () => ok(await client.getCapture(args.captureUuid))),
  );

  server.registerTool(
    'capture_complete',
    {
      title: '초안 봉인(검토 대기)',
      description:
        '초안을 READY_FOR_REVIEW 로 봉인한다. 문제 해결 여부와 무관. 참조한 근거(evidenceRefs)가 모두 업로드돼 있어야 한다(아니면 422). 사용자 지시 없이 자동 호출하지 말 것.',
      inputSchema: {
        captureUuid: z.string().uuid(),
        expectedRevision: z.number().int().optional(),
      },
    },
    (args) =>
      guard(async () => {
        const sealed = await client.completeCapture(
          args.captureUuid,
          { expectedRevision: args.expectedRevision },
          idempotencyKeyFor('capture.complete', args.captureUuid),
        );
        return ok({ captureUuid: sealed.captureUuid, status: sealed.status, revision: sealed.revision });
      }),
  );

  return server;
}
