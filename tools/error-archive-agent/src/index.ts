#!/usr/bin/env node
/**
 * 엔트리포인트.
 *   error-archive-agent               → MCP stdio 서버(Claude Code 가 연결) + 수집 이벤트 주기 flush
 *   error-archive-agent doctor        → 설정·연결 진단(1회성 CLI)
 *   error-archive-agent connect-project [path] → 프로젝트 연결 승인(터미널)
 *   error-archive-agent hook          → Claude Code Hook 어댑터(stdin JSON → 로컬 큐 적재만)
 *   error-archive-agent flush         → 수집 큐를 서버 인박스로 즉시 전송
 *   error-archive-agent install-hooks → ~/.claude/settings.json 에 Hook 설정 주입
 *
 * ⚠️ MCP 모드에서 stdout 은 프로토콜 채널이다. 로그/진단은 반드시 stderr 로만 낸다.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { ApiError, createApiClient } from './apiClient.js';
import { ConfigError, loadConfig, maskToken } from './config.js';
import { accountNamespace, EventQueue } from './eventQueue.js';
import { flushQueue } from './eventSender.js';
import { collectionEnabled, isSensitive, normalizeHookEvent, shouldCollect, type HookInput } from './hook.js';
import { resolveClientInfo } from './install.js';
import { ProjectContext } from './projectContext.js';
import { createServer } from './server.js';
import { recordContext } from './sessionContext.js';

const FLUSH_INTERVAL_MS = 10_000;

async function runDoctor(): Promise<number> {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    if (err instanceof ConfigError) {
      process.stderr.write(`✗ 설정 오류: ${err.message}\n`);
      return 2;
    }
    throw err;
  }
  process.stderr.write(`base URL : ${config.baseUrl}\n`);
  process.stderr.write(`token    : ${maskToken(config.token)}\n`);

  // 세션 식별 진단(#182) — 내 세션 id 를 알면 다중 동시 세션에서 '내 문맥'만 조회해 오귀속을 막는다.
  // 모르면(없음) 모호 시 미귀속으로 떨어진다. CLAUDE_CODE_SESSION_ID 는 Claude Code 가 자동 주입(2단계 전제).
  const envExplicit = process.env.ERROR_ARCHIVE_SESSION_ID?.trim();
  const envClaude = process.env.CLAUDE_CODE_SESSION_ID?.trim();
  const sessionSource = !config.sessionId
    ? '없음 — 다중 세션 모호 시 미귀속'
    : envExplicit && envExplicit === config.sessionId
      ? 'ERROR_ARCHIVE_SESSION_ID(명시)'
      : envClaude && envClaude === config.sessionId
        ? 'CLAUDE_CODE_SESSION_ID(Claude Code 자동)'
        : '설정 파일';
  process.stderr.write(`session  : ${config.sessionId ?? '(없음)'} · 출처=${sessionSource}\n`);
  process.stderr.write(`  CLAUDE_CODE_SESSION_ID : ${envClaude ? '감지됨' : '없음(이 프로세스 환경에 미주입)'}\n`);

  const install = resolveClientInfo();
  process.stderr.write(`install  : ${install.installLabel ?? '(label 생략)'} · ${install.kind} ${install.version ?? ''}\n`);
  const client = createApiClient(config, undefined, install);
  try {
    const me = await client.whoami();
    process.stderr.write('✓ 연결 OK\n');
    process.stderr.write(`  userId      : ${me.userId}\n`);
    process.stderr.write(`  connectionId: ${me.connectionId}\n`);
    process.stderr.write(`  scopes      : ${me.scopes.join(', ') || '(none)'}\n`);
    return 0;
  } catch (err) {
    if (err instanceof ApiError) {
      process.stderr.write(`✗ 연결 실패(status=${err.status}${err.code ? `, code=${err.code}` : ''}): ${err.message}\n`);
      return 1;
    }
    throw err;
  }
}

async function runServer(): Promise<void> {
  // 설정이 없으면 즉시 실패(MCP 핸드셰이크 전에 명확히 알림).
  const config = loadConfig();
  const client = createApiClient(config, undefined, resolveClientInfo());
  const ns = accountNamespace(config.baseUrl, config.token);
  const project = new ProjectContext(client, {
    baseUrl: config.baseUrl,
    projectRoot: config.projectRoot,
    sessionId: config.sessionId,
    // Hook 이 기록한 실제 cwd·session_id 를 읽어 문맥 복원(G). MCP 서버 프로세스의 process.cwd() 는
    // Claude Code 작업 폴더가 아닐 수 있으므로 hook cwd 를 더 신뢰한다.
    hookNamespace: ns,
  });
  // 수집 이벤트 큐/sender — capture_list_events 조회 전 flush + 주기 flush 로 서버 인박스 반영.
  const queue = new EventQueue(ns);
  const flushEvents = async () => {
    try {
      await flushQueue(client, queue);
    } catch (err) {
      process.stderr.write(`[events] flush 오류(무시): ${err instanceof Error ? err.message : String(err)}\n`);
    }
  };

  const server = createServer(client, project, flushEvents);
  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write(`error-archive-agent MCP 서버 시작(base=${config.baseUrl}, token=${maskToken(config.token)})\n`);

  void flushEvents();
  const timer = setInterval(() => void flushEvents(), FLUSH_INTERVAL_MS);
  if (typeof timer.unref === 'function') timer.unref();
}

/** CLI 프로젝트 연결 승인(1회성) — 터미널에서 사용자가 직접 실행하는 명시적 승인 경로(elicitation 폴백). */
async function runConnectProject(): Promise<number> {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    if (err instanceof ConfigError) {
      process.stderr.write(`✗ 설정 오류: ${err.message}\n`);
      return 2;
    }
    throw err;
  }
  const client = createApiClient(config, undefined, resolveClientInfo());
  const project = new ProjectContext(client, {
    baseUrl: config.baseUrl,
    projectRoot: config.projectRoot,
    sessionId: config.sessionId,
  });
  const resolved = project.detect(process.argv[3]); // 선택: 경로 인자
  if (!resolved) {
    process.stderr.write('✗ git 저장소를 찾지 못했습니다. `error-archive-agent connect-project <경로>` 로 지정하세요.\n');
    return 1;
  }
  process.stderr.write(`프로젝트 : ${resolved.projectKey}\n경로     : ${resolved.gitRoot}\n`);
  if (resolved.remote == null) process.stderr.write('(원격 없음 — 로컬 전용 프로젝트 키)\n');
  if (await project.isApproved(resolved)) {
    process.stderr.write('✓ 이미 연결됨.\n');
    return 0;
  }
  // 수집 고지(Q4) — 승인은 "귀속 동의"일 뿐 아니라 "데이터 수집 동의"도 포함함을 명확히 알린다.
  process.stderr.write(
    '\n연결하면 이 프로젝트에서:\n' +
      '  • Capture 가 이 프로젝트에 귀속됩니다.\n' +
      '  • Hook 이 도구 실행의 입력/출력을 **마스킹·절단**해 이벤트로 수집합니다(시크릿은 가려짐).\n' +
      '  • .env·키·크리덴셜 등 민감 경로를 건드리는 이벤트는 수집에서 제외됩니다.\n' +
      '  • 사용자 프롬프트 원문은 저장·전송하지 않습니다.\n' +
      '  • 수집을 끄려면 ERROR_ARCHIVE_COLLECT=off (추가 제외는 ERROR_ARCHIVE_COLLECT_EXCLUDE).\n',
  );
  if (!collectionEnabled()) {
    process.stderr.write('  ⓘ 현재 ERROR_ARCHIVE_COLLECT=off — 승인해도 이벤트 수집은 중지 상태입니다.\n');
  }
  const rl = createInterface({ input: process.stdin, output: process.stderr });
  const answer = await new Promise<string>((res) => rl.question('\n이 프로젝트로 연결·수집에 동의할까요? [y/N] ', res));
  rl.close();
  if (answer.trim().toLowerCase() === 'y' || answer.trim().toLowerCase() === 'yes') {
    await project.approve(resolved);
    process.stderr.write('✓ 연결 승인됨. 이후 Capture 가 이 프로젝트에 귀속됩니다.\n');
    return 0;
  }
  process.stderr.write('취소됨(저장 안 함).\n');
  return 1;
}

/** stdin 전체 읽기(Hook JSON). */
async function readStdin(): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of process.stdin) chunks.push(chunk as Buffer);
  return Buffer.concat(chunks).toString('utf8');
}

/**
 * ARC 자동기록 정책 — UserPromptSubmit Hook 이 모델에게 일시 문맥(additionalContext)으로 전달한다.
 * 사용자 프롬프트 원문을 저장·전송하지 않고(개인정보 최소화, 결정 3), "무엇을 기록하는가"의 정책만 알린다.
 * 명령문이 아니라 **사실 서술**로 쓴다(명령형 system 문구는 프롬프트 인젝션 방어를 자극할 수 있어 — Claude Code 문서).
 */
const RECORDING_POLICY =
  '[Error Archive 자동기록] 이 세션에는 문제 해결 경험을 남기는 MCP 도구가 연결돼 있다' +
  '(capture_list_open, capture_start, capture_link_events, capture_add_attempt, capture_add_solution, capture_complete). ' +
  '이 연동의 기록 정책: 사용자가 버그를 제보했거나 AI 가 테스트·빌드 실패 등 오류를 발견해 원인 조사·수정·검증을 수행하는 작업은, ' +
  '사용자가 기록을 따로 요청하지 않아도 하나의 Capture 로 남기는 것이 대상이다. 같은 세션에서 진행 중인 Capture 는 ' +
  'capture_list_open 으로 확인할 수 있고, 같은 문제의 연속이면 그 captureUuid 를 재사용하며 원인이 다른 별개 문제면 새 Capture 로 분리한다. ' +
  '사용자가 수정 방향을 직접 선택했다면 그 판단과 이유도 같은 Capture 에 함께 남는다. 단순 질문·설명·의도적 실패 테스트·일시적 환경 오류는 대상이 아니다. ' +
  '문제가 시각적 상태(깨진 UI·렌더 결과 스크린샷, 읽은 이미지 등)와 관련됐다면 그 재현 근거도 1급 근거이므로 해당 파일 경로로 capture_attach_evidence 에 첨부해 같은 Capture 에 남긴다. ' +
  '이런 파일(세션 중 찍은 스크린샷·읽은 이미지)은 디스크에 보존되어 capture 작성 시점에도 경로가 유효하다. ' +
  '정식 Case 반영은 사용자 검토 단계에서 이뤄지므로 AI 는 Capture 작성·봉인(capture_complete)까지만 담당하며, 미해결로 끝난 조사도 봉인해 남길 수 있다.';

/**
 * Hook 어댑터(P1-4 + G + ARC) — Claude Code 가 Hook 으로 호출. stdin JSON 에서:
 *   1) 세션 문맥({session_id, cwd}) 을 로컬 저장소에 기록(G — 수집 대상 여부와 무관, MCP 서버가 읽어 복원).
 *   2) UserPromptSubmit 이면 원문을 저장하지 않고 자동기록 정책만 additionalContext 로 주입(ARC 결정 3).
 *   3) 수집 대상(PostToolUse·PostToolUseFailure·Stop)이면 정규화해 로컬 큐에 적재(네트워크 X).
 * 항상 0 종료(Claude Code 흐름을 막지 않음). 오류여도 조용히 통과.
 */
async function runHook(): Promise<number> {
  try {
    const raw = await readStdin();
    if (!raw.trim()) return 0;
    const input = JSON.parse(raw) as HookInput;
    const config = loadConfig();
    const ns = accountNamespace(config.baseUrl, config.token);
    // (1) 실제 cwd·session_id 를 서버 프로세스로 넘기기 위해 기록(SessionStart 등 비수집 이벤트도 포함).
    try {
      recordContext(ns, { sessionId: input.session_id, cwd: input.cwd });
    } catch (err) {
      process.stderr.write(`[hook] 문맥 기록 실패(무시): ${err instanceof Error ? err.message : String(err)}\n`);
    }
    // (2) UserPromptSubmit: 프롬프트 원문은 적재·전송하지 않고(개인정보 최소화), 자동기록 정책만 전달.
    if (input.hook_event_name === 'UserPromptSubmit') {
      process.stdout.write(
        JSON.stringify({
          hookSpecificOutput: { hookEventName: 'UserPromptSubmit', additionalContext: RECORDING_POLICY },
        }),
      );
      return 0;
    }
    // (3) 수집 대상만 이벤트 큐에 적재. 단 수집 토글 off(Q4) 또는 민감 경로/명령 이벤트는 제외.
    if (!shouldCollect(input)) return 0;
    if (!collectionEnabled()) return 0; // 귀속 승인과 분리된 수집 토글 — 문맥은 기록, 적재만 중지
    if (isSensitive(input)) return 0; // .env·키·크리덴셜 등을 건드리는 이벤트는 수집 안 함(마스킹과 별개 2차 방어)
    const queue = new EventQueue(ns);
    queue.enqueue(normalizeHookEvent(input, queue.nextLocalSequence()));
  } catch (err) {
    process.stderr.write(`[hook] 적재 실패(무시): ${err instanceof Error ? err.message : String(err)}\n`);
  }
  return 0; // Hook 은 절대 실패로 종료하지 않는다
}

/** 큐를 서버로 즉시 flush(CLI 보조). */
async function runFlush(): Promise<number> {
  const config = loadConfig();
  const client = createApiClient(config, undefined, resolveClientInfo());
  const queue = new EventQueue(accountNamespace(config.baseUrl, config.token));
  const r = await flushQueue(client, queue);
  process.stderr.write(`[events] sent=${r.sent} rejected=${r.rejected} pending=${r.pending}\n`);
  return 0;
}

/** 설치할 Hook 이벤트. UserPromptSubmit=사용자 제보 감지+정책 주입(ARC), PostToolUseFailure=도구 실패(에러) 수집. */
const INSTALLED_HOOK_EVENTS = ['SessionStart', 'UserPromptSubmit', 'PostToolUse', 'PostToolUseFailure', 'Stop'];

/**
 * 자동기록 Skill(error-archive-recording/SKILL.md)을 사용자 skills 디렉터리에 설치한다(선택, opt-in).
 *
 * ablation 결과 Skill 없이도 additionalContext(UserPromptSubmit 정책)+도구 설명만으로 자발 기록이 100% 재현돼,
 * **기본 설치하지 않는다**(과설계 회피). 상세 절차 가이드를 원하면 `install-hooks --with-skill` 로 설치한다.
 * 실패해도 비치명적(Hook·additionalContext 로도 동작).
 */
function installSkill(): string | null {
  try {
    const src = fileURLToPath(new URL('../skill/error-archive-recording/SKILL.md', import.meta.url));
    const skillsDir = process.env.CLAUDE_SKILLS_DIR ?? join(homedir(), '.claude', 'skills');
    const destDir = join(skillsDir, 'error-archive-recording');
    mkdirSync(destDir, { recursive: true });
    const dest = join(destDir, 'SKILL.md');
    writeFileSync(dest, readFileSync(src, 'utf8'));
    return dest;
  } catch (err) {
    process.stderr.write(`[install] Skill 설치 실패(무시): ${err instanceof Error ? err.message : String(err)}\n`);
    return null;
  }
}

/**
 * Claude Code settings.json 에 Hook 설정 주입.
 *   SessionStart → 세션 시작 시 cwd·session_id 기록(첫 도구 실행 전에 프로젝트 문맥 확보, G).
 *   UserPromptSubmit → 사용자 제보형 버그 감지 + 자동기록 정책 주입(원문 미저장, ARC 결정 3).
 *   PostToolUse·PostToolUseFailure → 도구 성공·실패 이벤트 수집 + 문맥 갱신(실패는 PostToolUseFailure 로 발화).
 *   Stop → 세션 종료 신호 수집.
 * 모두 `error-archive-agent hook` 으로 연결.
 */
async function runInstallHooks(): Promise<number> {
  const settingsPath = process.env.CLAUDE_SETTINGS ?? join(homedir(), '.claude', 'settings.json');
  const hookCmd = { type: 'command', command: 'error-archive-agent hook' };
  let settings: Record<string, unknown> = {};
  try {
    settings = JSON.parse(readFileSync(settingsPath, 'utf8')) as Record<string, unknown>;
  } catch {
    settings = {};
  }
  const hooks = (settings.hooks ?? {}) as Record<string, unknown>;
  for (const event of INSTALLED_HOOK_EVENTS) {
    const arr = Array.isArray(hooks[event]) ? (hooks[event] as unknown[]) : [];
    const already = arr.some((m) => JSON.stringify(m).includes('error-archive-agent hook'));
    if (!already) arr.push({ hooks: [hookCmd] });
    hooks[event] = arr;
  }
  settings.hooks = hooks;
  try {
    writeFileSync(settingsPath, `${JSON.stringify(settings, null, 2)}\n`);
    process.stderr.write(`✓ Hook 설치: ${settingsPath} (${INSTALLED_HOOK_EVENTS.join('·')} → error-archive-agent hook)\n`);
    // Skill 은 기본 미설치(opt-in). --with-skill 일 때만 설치.
    if (process.argv.includes('--with-skill')) {
      const skillPath = installSkill();
      if (skillPath) process.stderr.write(`✓ Skill 설치: ${skillPath} (error-archive-recording)\n`);
    } else {
      process.stderr.write('ⓘ 자동기록은 Hook 만으로 동작합니다. 상세 절차 Skill 을 쓰려면: install-hooks --with-skill\n');
    }
    return 0;
  } catch (err) {
    process.stderr.write(`✗ settings.json 쓰기 실패(${settingsPath}): ${err instanceof Error ? err.message : String(err)}\n`);
    process.stderr.write(`수동 설정: ${INSTALLED_HOOK_EVENTS.join('·')} Hook 에 \`error-archive-agent hook\` 커맨드를 추가하세요.\n`);
    return 1;
  }
}

async function main(): Promise<void> {
  const command = process.argv[2];
  if (command === 'doctor') {
    process.exit(await runDoctor());
  }
  if (command === 'connect-project') {
    process.exit(await runConnectProject());
  }
  if (command === 'hook') {
    process.exit(await runHook());
  }
  if (command === 'flush') {
    process.exit(await runFlush());
  }
  if (command === 'install-hooks') {
    process.exit(await runInstallHooks());
  }
  if (command && command !== 'serve') {
    process.stderr.write(`알 수 없는 명령: ${command}\n사용법: error-archive-agent [serve|doctor|connect-project [path]|hook|flush|install-hooks]\n`);
    process.exit(2);
  }
  try {
    await runServer();
  } catch (err) {
    if (err instanceof ConfigError) {
      process.stderr.write(`✗ 설정 오류: ${err.message}\n설정을 확인하려면: error-archive-agent doctor\n`);
      process.exit(2);
    }
    throw err;
  }
}

main().catch((err) => {
  process.stderr.write(`치명적 오류: ${err instanceof Error ? err.stack ?? err.message : String(err)}\n`);
  process.exit(1);
});
