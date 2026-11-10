#!/usr/bin/env node
/**
 * 엔트리포인트.
 *   error-archive-agent            → MCP stdio 서버(Claude Code 가 연결)
 *   error-archive-agent doctor     → 설정·연결 진단(1회성 CLI)
 *
 * ⚠️ MCP 모드에서 stdout 은 프로토콜 채널이다. 로그/진단은 반드시 stderr 로만 낸다.
 */
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { ApiError, createApiClient } from './apiClient.js';
import { ConfigError, loadConfig, maskToken } from './config.js';
import { runHook } from './hooks/run.js';
import { resolveClientInfo } from './install.js';
import { resolveQueuePath } from './queue/path.js';
import { QueueSender } from './queue/sender.js';
import { QueueStore } from './queue/store.js';
import { createServer } from './server.js';

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

/** 로컬 큐 상태 요약(네트워크 불필요). 사람 개입이 필요한 항목을 드러낸다 — 조용한 폐기는 없다. */
function runStatus(): number {
  const path = resolveQueuePath();
  const store = new QueueStore(path);
  try {
    const c = store.counts();
    const backlogMiB = (store.backlogBytes() / (1024 * 1024)).toFixed(2);
    process.stderr.write(`로컬 큐: ${path}\n`);
    process.stderr.write(`  대기(PENDING)     : ${c.PENDING}\n`);
    process.stderr.write(`  전송중(INFLIGHT)   : ${c.INFLIGHT}\n`);
    process.stderr.write(`  전송완료(SENT)     : ${c.SENT}\n`);
    process.stderr.write(`  차단(BLOCKED)      : ${c.BLOCKED}\n`);
    process.stderr.write(`  확인필요(NEEDS_ATTENTION): ${c.NEEDS_ATTENTION}\n`);
    process.stderr.write(`  미전송 backlog     : ${backlogMiB} MiB\n`);
    const attn = store.needsAttention();
    if (attn.length > 0) {
      process.stderr.write(`\n확인 필요(자동 폐기하지 않음):\n`);
      for (const it of attn) {
        process.stderr.write(`  #${it.id} ${it.op}${it.captureUuid ? ` (${it.captureUuid})` : ''} — ${it.lastError ?? ''}\n`);
      }
    }
    return 0;
  } finally {
    store.close();
  }
}

/** 큐를 한 차례 비운다(flush). 네트워크 복구 후 수동 전송·CI 점검용. 결과 요약을 stderr 로. */
async function runFlush(): Promise<number> {
  const config = loadConfig();
  const client = createApiClient(config);
  const store = new QueueStore(resolveQueuePath());
  try {
    const r = await new QueueSender(store, client).drain();
    process.stderr.write(`flush: 전송 ${r.sent}, 재시도예약 ${r.retried}, 실패 ${r.failed}, 차단 ${r.blocked}\n`);
    const attn = store.counts().NEEDS_ATTENTION;
    if (attn > 0) process.stderr.write(`확인 필요 ${attn}건 — 'error-archive-agent status' 로 확인\n`);
    return attn > 0 ? 1 : 0;
  } finally {
    store.close();
  }
}

async function runServer(): Promise<void> {
  // 설정이 없으면 즉시 실패(MCP 핸드셰이크 전에 명확히 알림).
  const config = loadConfig();
  const client = createApiClient(config, undefined, resolveClientInfo());
  const server = createServer(client);
  const transport = new StdioServerTransport();
  await server.connect(transport);
  process.stderr.write(`error-archive-agent MCP 서버 시작(base=${config.baseUrl}, token=${maskToken(config.token)})\n`);
}

async function main(): Promise<void> {
  const command = process.argv[2];
  if (command === 'doctor') {
    process.exit(await runDoctor());
  }
  if (command === 'hook') {
    process.exit(await runHook());
  }
  if (command === 'status') {
    process.exit(runStatus());
  }
  if (command === 'flush') {
    try {
      process.exit(await runFlush());
    } catch (err) {
      if (err instanceof ConfigError) {
        process.stderr.write(`✗ 설정 오류: ${err.message}\n`);
        process.exit(2);
      }
      throw err;
    }
  }
  if (command && command !== 'serve') {
    process.stderr.write(`알 수 없는 명령: ${command}\n사용법: error-archive-agent [serve|doctor|status|flush|hook]\n`);
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
