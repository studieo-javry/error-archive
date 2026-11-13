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
import { readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { ApiError, createApiClient } from './apiClient.js';
import { ConfigError, loadConfig, maskToken } from './config.js';
import { accountNamespace, EventQueue } from './eventQueue.js';
import { flushQueue } from './eventSender.js';
import { normalizeHookEvent, shouldCollect, type HookInput } from './hook.js';
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
  const rl = createInterface({ input: process.stdin, output: process.stderr });
  const answer = await new Promise<string>((res) => rl.question('이 프로젝트로 연결할까요? [y/N] ', res));
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
 * Hook 어댑터(P1-4 + G) — Claude Code 가 Hook 으로 호출. stdin JSON 에서:
 *   1) 세션 문맥({session_id, cwd}) 을 로컬 저장소에 기록(G — 수집 대상 여부와 무관, MCP 서버가 읽어 복원).
 *   2) 수집 대상(PostToolUse·Stop)이면 정규화해 로컬 큐에 적재(네트워크 X).
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
    // (2) 수집 대상만 이벤트 큐에 적재.
    if (!shouldCollect(input)) return 0;
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

/** Claude Code settings.json 에 Hook(PostToolUse·Stop → `error-archive-agent hook`) 설정 주입. */
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
  for (const event of ['PostToolUse', 'Stop']) {
    const arr = Array.isArray(hooks[event]) ? (hooks[event] as unknown[]) : [];
    const already = arr.some((m) => JSON.stringify(m).includes('error-archive-agent hook'));
    if (!already) arr.push({ hooks: [hookCmd] });
    hooks[event] = arr;
  }
  settings.hooks = hooks;
  try {
    writeFileSync(settingsPath, `${JSON.stringify(settings, null, 2)}\n`);
    process.stderr.write(`✓ Hook 설치: ${settingsPath} (PostToolUse·Stop → error-archive-agent hook)\n`);
    return 0;
  } catch (err) {
    process.stderr.write(`✗ settings.json 쓰기 실패(${settingsPath}): ${err instanceof Error ? err.message : String(err)}\n`);
    process.stderr.write('수동 설정: PostToolUse·Stop Hook 에 `error-archive-agent hook` 커맨드를 추가하세요.\n');
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
