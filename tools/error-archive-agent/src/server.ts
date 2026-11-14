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
import type { ProjectContext } from './projectContext.js';
import { createRequire } from 'node:module';

/**
 * 단일 소스: MCP 핸드셰이크 버전을 package.json 에서 읽는다 → 버전 범프는 package.json 한 곳만.
 * createRequire 라 tsc rootDir 제약(JSON import TS6059)을 피하고, dist/src/설치본 모두 `../package.json`
 * 이 패키지 자신의 매니페스트로 해석된다.
 */
const { version: PKG_VERSION } = createRequire(import.meta.url)('../package.json') as { version: string };

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

export function createServer(
  client: ApiClient,
  project?: ProjectContext,
  flushEvents?: () => Promise<void>,
): McpServer {
  const server = new McpServer({ name: 'error-archive-agent', version: PKG_VERSION });

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
        '새 OPEN 초안을 만든다. 시작 전에 capture_list_open 으로 현재 세션의 진행 중 Capture 를 확인해, 같은 버그의 연속이면 새로 만들지 말고 그 captureUuid 에 이어서 기록하라(한 버그=한 Capture). ' +
        'captureUuid 는 서버가 아닌 이 도구가 부여하므로, 반환된 captureUuid 를 이후 attempt/solution/evidence/complete 에 전달하라. 근거 없는 원인 단정은 넣지 말 것.',
      inputSchema: {
        title: z.string().optional(),
        project: z.string().optional(),
        sessionUuid: z.string().uuid().optional().describe('이 초안을 이을 세션(선택)'),
        projectBindingId: z.number().int().positive().optional()
          .describe('이 초안을 귀속시킬 프로젝트(ProjectBinding) id. 미지정 시 세션 프로젝트를 상속한다.'),
        ...CONTENT_SHAPE,
      },
    },
    (args) =>
      guard(async () => {
        const captureUuid = newUuid();
        // 세션 문맥 자동 전달: 명시 sessionUuid 가 없으면 승인된 프로젝트 세션을 자동 주입(미승인이면 미귀속).
        const sessionUuid = args.sessionUuid ?? (project ? await project.ensureSession() : undefined);
        const created = await client.createCapture(
          {
            captureUuid,
            title: args.title,
            project: args.project,
            content: buildCaptureContent(args),
            sessionUuid,
            projectBindingId: args.projectBindingId,
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
    'capture_update_attempt',
    {
      title: '시도 수정',
      description:
        '기존 시도(Attempt)를 수정한다. 잘못된 evidenceRefs(근거 clientArtifactId) 정정 등에 사용 — 예: complete 가 EVIDENCE_NOT_READY(missingEvidenceRefs) 로 실패할 때. ' +
        '전체 detail 을 교체하므로 유지할 값도 함께 보낸다(먼저 capture_get 으로 현재 값 확인 권장).',
      inputSchema: {
        captureUuid: z.string().uuid(),
        clientAttemptId: z.string().describe('수정할 시도의 clientAttemptId'),
        result: RESULT,
        attemptType: z.string().optional(),
        title: z.string(),
        hypothesis: z.string().optional(),
        action: z.string().optional(),
        insight: z.string().optional(),
        nextStep: z.string().optional(),
        evidenceRefs: z.array(z.string()).optional().describe('연결할 근거(clientArtifactId). 봉인 전 업로드돼야 함'),
      },
    },
    (args) =>
      guard(async () => {
        const saved = await client.updateAttempt(args.captureUuid, args.clientAttemptId, {
          result: args.result,
          attemptType: args.attemptType,
          detail: buildAttemptDetail(args),
        });
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
        verificationTestId: z.string().optional(),
        verificationExecuted: z.boolean().optional(),
        verificationRanAt: z.string().optional(),
        verificationCommitSha: z.string().optional(),
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
              testId: args.verificationTestId,
              executed: args.verificationExecuted,
              ranAt: args.verificationRanAt,
              commitSha: args.verificationCommitSha,
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
    'capture_update_solution',
    {
      title: '해결 후보 수정',
      description:
        '기존 해결 후보(Solution)를 수정한다(통째 교체). 잘못 기록한 검증 정보(verification) 정정 등에 사용 — ' +
        'capture_update_attempt 의 Solution 짝. 유지할 값도 함께 보낸다(먼저 capture_get 으로 현재 값 확인 권장). ' +
        '검증 결과(PASSED)는 실제 실행(executed=true) + 근거가 있을 때만.',
      inputSchema: {
        captureUuid: z.string().uuid(),
        clientSolutionId: z.string().describe('수정할 해결 후보의 clientSolutionId'),
        title: z.string(),
        description: z.string().optional(),
        clientAttemptIds: z.array(z.string()).min(1).describe('기여한 시도(clientAttemptId) — 최소 1개'),
        verificationResult: VERIFICATION_RESULT.optional(),
        verificationScope: VERIFICATION_SCOPE.optional(),
        verificationCommand: z.string().optional(),
        verificationTestId: z.string().optional(),
        verificationExecuted: z.boolean().optional(),
        verificationRanAt: z.string().optional(),
        verificationCommitSha: z.string().optional(),
        verificationEvidence: z.string().optional(),
      },
    },
    (args) =>
      guard(async () => {
        const verification = args.verificationResult
          ? {
              result: args.verificationResult,
              scope: args.verificationScope,
              command: args.verificationCommand,
              testId: args.verificationTestId,
              executed: args.verificationExecuted,
              ranAt: args.verificationRanAt,
              commitSha: args.verificationCommitSha,
              evidence: args.verificationEvidence,
            }
          : undefined;
        const saved = await client.updateSolution(args.captureUuid, args.clientSolutionId, {
          title: args.title,
          content: buildSolutionContent({
            description: args.description,
            clientAttemptIds: args.clientAttemptIds,
            verification,
          }),
        });
        return ok({ clientSolutionId: saved.clientSolutionId, title: saved.title });
      }),
  );

  server.registerTool(
    'capture_attach_evidence',
    {
      title: '근거 첨부',
      description:
        '로컬 파일(스크린샷·로그·diff·JSON)을 초안 근거로 업로드한다. 시각적 재현 근거(깨진 UI·렌더 결과 스크린샷, 읽은 이미지)도 1급 근거이니 문제가 시각 상태와 관련되면 그 파일 경로로 첨부하라. ' +
        '반환된 clientArtifactId 를 시도의 evidenceRefs 에 연결하라. 허용: PNG/JPEG/WebP·UTF-8 텍스트/로그/diff·JSON(파일당 10MiB).',
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
    'capture_list_open',
    {
      title: '진행 중 Capture 조회',
      description:
        '현재 세션에서 진행 중(OPEN)인 Capture 후보를 조회한다(최근 갱신순). 새 문제 해결을 기록하기 전에 먼저 호출해, ' +
        '지금 다루는 문제가 기존 진행 중 Capture 와 같은 버그면 그 captureUuid 를 재사용하고(한 버그=한 Capture, 중복 난립 방지), ' +
        '원인이 다른 별개 문제면 capture_start 로 새로 시작한다. 기본은 현재 세션으로 좁혀 보여주고, allSessions=true 면 이 연결의 모든 OPEN 을 본다. ' +
        '봉인된(READY_FOR_REVIEW)·반영된·폐기된 Capture 는 포함하지 않는다.',
      inputSchema: {
        allSessions: z
          .boolean()
          .optional()
          .describe('현재 세션으로 좁히지 않고 이 연결의 모든 OPEN Capture 조회(기본 false)'),
      },
    },
    (args) =>
      guard(async () => {
        const sessionUuid = args.allSessions ? undefined : project ? await project.ensureSession() : undefined;
        return ok(await client.listOpenCaptures(sessionUuid));
      }),
  );

  server.registerTool(
    'capture_complete',
    {
      title: '초안 봉인(검토 대기)',
      description:
        '초안을 READY_FOR_REVIEW 로 봉인한다. 문제 해결 여부와 무관(해결/미해결 모두 봉인 가능 — 미해결은 조사 내용·미해결 사유를 남기고 봉인). ' +
        '참조한 근거(evidenceRefs)가 모두 업로드돼 있어야 한다(아니면 422). ' +
        '한 번의 문제 해결 작업(조사·수정·검증, 또는 조사 끝 미해결 결론)이 끝났을 때 봉인한다 — 사용자가 기록을 따로 요청하지 않아도 된다. ' +
        '단, 아직 원인 조사 중이거나 사용자 결정(수정 방향)을 기다리는 중, 또는 단지 한 턴이 끝났다는 이유만으로 봉인하지 말 것(그 동안은 OPEN 유지).',
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

  server.registerTool(
    'find_cases',
    {
      title: '과거 사례 검색',
      description:
        '본인이 전에 해결한 Case 를 검색한다(과거 해결경험 재활용). query 로 제목·설명·예외로 유사 오류를 찾고, ' +
        'projectBindingId 로 특정 프로젝트로 좁힐 수 있다(선택 — 미지정 시 프로젝트 무관 전체). 공개 사례·타인 데이터는 조회되지 않는다.',
      inputSchema: {
        query: z.string().optional(),
        projectBindingId: z.number().int().positive().optional(),
        limit: z.number().int().min(1).max(100).optional(),
      },
    },
    (args) => guard(async () => ok(await client.searchCases(args.query, args.projectBindingId, args.limit))),
  );

  server.registerTool(
    'get_case',
    {
      title: '과거 사례 상세',
      description:
        '과거 Case 상세를 조회한다 — Step 본문·Solution 설명·검증 근거까지 포함해 해결 과정을 그대로 참고할 수 있다. 본인 소유만(아니면 404).',
      inputSchema: { caseId: z.number().int().positive() },
    },
    (args) => guard(async () => ok(await client.getCase(args.caseId))),
  );

  server.registerTool(
    'connect_project',
    {
      title: '프로젝트 연결(승인)',
      description:
        '현재 작업 폴더(git)를 Error Archive 프로젝트로 연결한다. **사용자 승인**이 필요하며, 승인 후 이 세션의 ' +
        'Capture 가 자동으로 그 프로젝트에 귀속된다. path 로 폴더를 지정할 수 있고, disconnect=true 로 해제한다.',
      inputSchema: {
        path: z.string().optional().describe('git 작업 폴더 경로(생략 시 설정 projectRoot·현재 경로로 자동 식별)'),
        disconnect: z.boolean().optional().describe('true 면 연결 해제'),
      },
    },
    (args) =>
      guard(async () => {
        if (!project) return ok({ enabled: false, message: '프로젝트 문맥 비활성(설정 없음).' });
        const resolved = project.detect(args.path);
        if (!resolved) {
          return ok({ detected: false, message: 'git 저장소를 찾지 못했습니다. path 로 작업 폴더를 지정하세요.' });
        }
        const base = { gitRoot: resolved.gitRoot, projectKey: resolved.projectKey, remote: resolved.remote, trust: resolved.trust };
        if (args.disconnect) {
          await project.revoke(resolved);
          return ok({ ...base, connected: false, message: '연결 해제됨.' });
        }
        if (await project.isApproved(resolved)) {
          return ok({ ...base, connected: true, message: '이미 연결됨.' });
        }
        const decision = await elicitProjectApproval(server, resolved.gitRoot, resolved.projectKey);
        if (decision === 'accept') {
          await project.approve(resolved);
          return ok({ ...base, connected: true, message: '연결 승인됨. 이후 Capture 가 이 프로젝트에 귀속됩니다.' });
        }
        if (decision === 'unsupported') {
          return ok({
            ...base, connected: false, needsApproval: true,
            message: '이 클라이언트는 인라인 승인을 지원하지 않습니다. 터미널에서 `npx -y @error-archive/agent connect-project` 를 실행해 승인하세요.',
          });
        }
        return ok({ ...base, connected: false, message: '승인되지 않았습니다(사용자 취소/거절).' });
      }),
  );

  server.registerTool(
    'capture_list_events',
    {
      title: '수집 이벤트 조회(미분류)',
      description:
        '아직 Capture 에 연결되지 않은, 자동 수집된 Hook 이벤트를 조회한다. 지금 처리 중인 오류의 해결 과정에 ' +
        '해당하는 이벤트를 **고르기 위한 후보**다. 같은 세션이라고 전부 연결하지 말고, 이 오류와 관련된 것만 골라라. sessionId 로 현재 세션만 좁힐 수 있다.',
      inputSchema: {
        sessionId: z.string().optional(),
        limit: z.number().int().min(1).max(100).optional(),
      },
    },
    (args) =>
      guard(async () => {
        if (flushEvents) await flushEvents(); // 지연 전송 이벤트까지 인박스 반영 후 조회
        return ok(await client.listEvents('UNCLASSIFIED', args.sessionId, args.limit));
      }),
  );

  server.registerTool(
    'capture_link_events',
    {
      title: '이벤트를 Capture 에 연결',
      description:
        '선택한 수집 이벤트를 이 Capture 의 근거로 연결한다. **같은 오류 해결 과정에 속한 이벤트만** 넘겨라. ' +
        '이미 다른 Capture 에 속한 이벤트는 skipped 로 돌아온다(가로채지 않음).',
      inputSchema: {
        captureUuid: z.string().uuid(),
        clientEventIds: z.array(z.string()).min(1),
      },
    },
    (args) => guard(async () => ok(await client.linkEvents(args.captureUuid, args.clientEventIds))),
  );

  return server;
}

/** MCP elicitation 으로 실제 사용자 승인을 받는다. 미지원 클라이언트면 'unsupported'(CLI 폴백). */
async function elicitProjectApproval(
  server: McpServer,
  gitRoot: string,
  projectKey: string,
): Promise<'accept' | 'decline' | 'unsupported'> {
  try {
    const res = await server.server.elicitInput({
      message: `이 작업 폴더를 Error Archive 프로젝트로 연결할까요?\n경로: ${gitRoot}\n프로젝트: ${projectKey}`,
      requestedSchema: {
        type: 'object',
        properties: { connect: { type: 'boolean', description: '연결을 승인하면 true' } },
        required: ['connect'],
      },
    });
    if (res.action === 'accept' && (res.content as { connect?: boolean } | undefined)?.connect === true) return 'accept';
    return 'decline';
  } catch {
    return 'unsupported';
  }
}
